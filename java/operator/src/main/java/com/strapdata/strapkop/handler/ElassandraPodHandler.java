package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.Pod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.kubernetes.client.models.V1PersistentVolumeClaim;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodCondition;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.vavr.collection.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Track Elassandra POD status to update the ElassandraNodeStatusCache that trigger Cassandra status pooling.
 * When POD is ready => Add ElassandraNodeStatus in status UNKNOWN in the cache to pool Cassandra status
 * When POD is deleted => trigger the ElassandraPodDeletedReconcilier to manage PVC lifecycle
 * When POD is pending => trigger the ElassandraPodUnscheduledReconcilier to manage eventual rollback.
 */
@Handler
public class ElassandraPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {

    // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-phase
    private static final String POD_PENDING_PHASE = "Pending";
    private static final String POD_RUNNING_PHASE = "Running";
    private static final String POD_SUCCESSDED_PHASE = "Succeeded";
    private static final String POD_FAILED_PHASE = "Failed";
    private static final String POD_UNKNOW_PHASE = "Unknown";

    public static final String CONTAINER_NAME = "elassandra";

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    MeterRegistry meterRegistry;
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        V1Pod pod = event.getResource();
        String parent = Pod.extractLabel(pod, OperatorLabels.PARENT);
        String clusterName = Pod.extractLabel(pod, OperatorLabels.CLUSTER);
        String datacenterName = Pod.extractLabel(pod, OperatorLabels.DATACENTER);
        String podName = pod.getMetadata().getName();
        String namespace = pod.getMetadata().getNamespace();

        ClusterKey clusterKey = new ClusterKey(clusterName, datacenterName);
        Key key = new Key(podName, namespace);

        logger.debug("ElassandraPod event type={} pod={}/{}", event.getType(), podName, namespace);

        if (event.isUpdate()) {
            if (POD_PENDING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) {
                if (event.getResource().getStatus() != null && event.getResource().getStatus().getConditions() != null) {
                    List<V1PodCondition> conditions = event.getResource().getStatus().getConditions();
                    Optional<V1PodCondition> scheduleFailed = conditions.stream()
                            .filter((condition) ->
                                    // Pod failed, so restart will probably go to an endless restart loop
                                    ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus()) ||
                                    // pod not scheduled
                                    "Unschedulable".equals(condition.getType()))
                            )
                            .findFirst();

                    logger.debug("Pending pod={}/{} conditions={}", podName, namespace, conditions);
                    if (scheduleFailed.isPresent()) {
                        workQueues.submit(
                                clusterKey,
                                dataCenterController.unschedulablePod(new Pod(pod, CONTAINER_NAME)));
                    }
                }
            }
        } else if (event.isDeletion()) {
            DataCenter dc = dataCenterCache.get(new Key(parent, namespace));
            workQueues.submit(
                    clusterKey,
                    freePodPvc(dc, new Pod(pod, CONTAINER_NAME)));
        }
    }

    public Completable freePodPvc(DataCenter dataCenter, Pod deletedPod) throws Exception {
        // delete PVC only if the node was decommissioned to avoid deleting PVC in unexpectedly killed pod during a DC ScaleDown
        switch (dataCenter.getSpec().getDecommissionPolicy()) {
            case KEEP_PVC:
                break;
            case BACKUP_AND_DELETE_PVC:
                // TODO
                break;
            case DELETE_PVC:
                List<V1PersistentVolumeClaim> pvcsToDelete = Stream.ofAll(k8sResourceUtils.listNamespacedPodsPersitentVolumeClaims(
                        dataCenter.getMetadata().getNamespace(),
                        null,
                        OperatorLabels.toSelector(ImmutableMap.of(
                                OperatorLabels.APP, "elassandra",
                                OperatorLabels.PARENT, deletedPod.getParent(),
                                OperatorLabels.RACK, deletedPod.getRack(),
                                OperatorLabels.RACKINDEX, Integer.toString(deletedPod.getRackIndex())))
                ))
                        .filter(pvc -> {
                            boolean match = pvc.getMetadata().getName().endsWith(deletedPod.getName());
                            logger.info("PVC={} will be deleted due to pod={}/{} deletion", pvc.getMetadata().getName(), deletedPod.getName(), deletedPod.getNamespace());
                            return match;
                        }).collect(Collectors.toList());

                if (pvcsToDelete.size() > 1) {
                    logger.error("Too many PVC found for deletion, cancel it ! (List of PVC = {})", pvcsToDelete);
                } else if (!pvcsToDelete.isEmpty()) {
                    return k8sResourceUtils.deletePersistentVolumeClaim(pvcsToDelete.get(0));
                }
        }
        return Completable.complete();
    }
}