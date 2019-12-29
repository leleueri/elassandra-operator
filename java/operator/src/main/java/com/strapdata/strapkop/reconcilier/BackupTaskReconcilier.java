package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.CloudStorageSecret;
import com.strapdata.model.backup.CommonBackupArguments;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.BackupTaskSpec;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class BackupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(BackupTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    
    public BackupTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final SidecarClientFactory sidecarClientFactory,
                                 final CustomObjectsApi customObjectsApi,
                                 final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "backup", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
    }

    /**
     * Execute backup concurrently on all nodes
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(Task task, DataCenter dc) throws ApiException {

        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // if there is no more we are done
        if (pods.isEmpty()) {
            return Single.just(TaskPhase.SUCCEED);
        }

        // TODO: better backup with sstableloader and progress tracking
        // right now it just call the backup api on every nodes sidecar in parallel
        final BackupTaskSpec backupSpec = task.getSpec().getBackup();
        final CloudStorageSecret cloudSecret = k8sResourceUtils.readAndValidateStorageSecret(task.getMetadata().getNamespace(), backupSpec.getSecretRef(), backupSpec.getProvider());

        return Observable.fromIterable(pods)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod -> {
                    final BackupArguments backupArguments = generateBackupArguments(
                            task.getMetadata().getName(),
                            backupSpec.getProvider(),
                            backupSpec.getBucket(),
                            OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                            pod,
                            cloudSecret);

                    return sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                            .backup(backupArguments)
                            .map(backupResponse -> {
                                logger.debug("Received backupSpec response with status = {}", backupResponse.getStatus());
                                boolean success = backupResponse.getStatus().equalsIgnoreCase("success");
                                if (!success)
                                    task.getStatus().setLastMessage("Basckup task="+task.getMetadata().getName()+" on pod="+pod+" failed");
                                return new Tuple2<String, Boolean>(pod, success);
                            });
                })
                .toList()
                .map(list -> {
                    // update pod status map a the end to avoid concurrency issue
                    TaskStatus status = task.getStatus();
                    Map<String, TaskPhase> podsMap = task.getStatus().getPods();
                    Map<String, TaskPhase> podStatus = status.getPods();
                    TaskPhase taskPhase = TaskPhase.SUCCEED;
                    for(Tuple2<String, Boolean> e : list) {
                        podsMap.put(e._1, e._2 ? TaskPhase.SUCCEED : TaskPhase.FAILED);
                        if (!e._2)
                            taskPhase = TaskPhase.FAILED;
                    }
                    return taskPhase;
                });
    }


    public static BackupArguments generateBackupArguments(final String tag, final StorageProvider provider,
                                                          final String target, final String cluster,
                                                          final String pod, final CloudStorageSecret cloudCredentials) {
        BackupArguments backupArguments = new BackupArguments();
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
        backupArguments.backupId = pod;
        backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
        backupArguments.cloudCredentials = cloudCredentials;
        return backupArguments;
    }
}
