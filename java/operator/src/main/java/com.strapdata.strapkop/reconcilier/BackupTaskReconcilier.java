package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.CommonBackupArguments;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.k8s.task.BackupTaskSpec;
import com.strapdata.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1Pod;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Singleton
@Infrastructure
public class BackupTaskReconcilier extends TaskReconcilier<BackupTask> {
    private static final Logger logger = LoggerFactory.getLogger(BackupTaskReconcilier.class);
    
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    private final CustomObjectsApi customObjectsApi;
    
    public BackupTaskReconcilier(K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory, CustomObjectsApi customObjectsApi) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
        this.customObjectsApi = customObjectsApi;
    }
    
    @Override
    protected void processSubmit(BackupTask task) throws ApiException {
        logger.info("processing backup task submit");
    
        if (task.getStatus() == null ||
                Strings.isNullOrEmpty(task.getStatus().getPhase())) {
        
            logger.debug("Reconciling Backup");
            callBackupApiAllPods(task).onErrorReturnItem(false).subscribe(success -> {
                task.setStatus(new TaskStatus().setPhase(success ? "Succeeded" : "Failed" ));
                logger.info("Backup name={} namespace={} success={}",
                        task.getMetadata().getName(), task.getMetadata().getNamespace(), success);
                customObjectsApi.replaceNamespacedCustomObjectStatus("stable.strapdata.com", "v1",
                        task.getMetadata().getNamespace(), "elassandra-backups", task.getMetadata().getName(), task);
            });
        }
    }
    
    @Override
    protected void processCancel(BackupTask task) {
        logger.info("processing backup task cancel");
    }
    
    private Single<Boolean> callBackupApiAllPods(final BackupTask backupTask) throws ApiException {
        final BackupTaskSpec backupSpec = backupTask.getSpec();
    
        final Map<String, String> labels = new HashMap<>(backupSpec.getSelector().getMatchLabels());
        
        // ensure we are not targeting another cluster
        labels.putAll(OperatorLabels.cluster(backupSpec.getCluster()));
        
        final String dataCenterPodsLabelSelector = OperatorLabels.toSelector(labels);
        
        final Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(backupTask.getMetadata().getNamespace(), null, dataCenterPodsLabelSelector);
        return Observable.fromIterable(pods)
                .observeOn(Schedulers.io())
                .flatMapSingle(pod -> callBackupApi(pod, backupTask))
                .all(Boolean::booleanValue);
    }
    
    private Single<Boolean> callBackupApi(final V1Pod pod, BackupTask backupTask) {
        try {
            BackupArguments backupArguments = generateBackupArguments(pod.getStatus().getPodIP(),
                    7199,
                    backupTask.getMetadata().getName(),
                    StorageProvider.valueOf(backupTask.getSpec().getBackupType()),
                    backupTask.getSpec().getTarget(),
                    pod.getMetadata().getLabels().get(OperatorLabels.PARENT));
            
            backupArguments.backupId = pod.getSpec().getHostname();
            backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
            return sidecarClientFactory.clientForPod(pod)
                    .backup(backupArguments)
                    .doOnSuccess(backupResponse -> logger.debug("received backup response with status = {}", backupResponse.getStatus()))
                    .map(backupResponse -> backupResponse.getStatus().equalsIgnoreCase("success"))
                    .onErrorReturn(throwable -> {
                        logger.warn("error occured from sidecar backup");
                        throwable.printStackTrace();
                        return false;
                    });
        } catch (MalformedURLException | UnknownHostException e) {
            return Single.error(e);
        }
    }
    
    public static BackupArguments generateBackupArguments(final String ip, final int port, final String tag, final StorageProvider provider, final String target, final String cluster) {
        BackupArguments backupArguments = new BackupArguments();
        backupArguments.setJmxServiceURLFromIp(ip, port);
        backupArguments.cassandraConfigDirectory = Paths.get("/etc/cassandra/");
        backupArguments.cassandraDirectory = Paths.get("/var/lib/cassandra/");
        backupArguments.sharedContainerPath = Paths.get("/tmp"); // elassandra can't ran as root
        backupArguments.snapshotTag = tag;
        backupArguments.storageProvider = provider;
        backupArguments.backupBucket = target;
        backupArguments.offlineSnapshot = false;
        backupArguments.account = "";
        backupArguments.secret = "";
        backupArguments.clusterId = cluster;
        return backupArguments;
    }
}
