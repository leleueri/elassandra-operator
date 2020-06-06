package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.cache.CheckPointCache;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.PodsAffinityPolicy;
import com.strapdata.strapkop.pipeline.WorkQueues;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.admission.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.AdmissionReviewBuilder;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;

@Controller("/datacenter")
public class DatacenterController {

    private final Logger logger = LoggerFactory.getLogger(DatacenterController.class);

    @Inject
    WorkQueues workQueue;

    @Inject
    CheckPointCache checkPointCache;

    @Inject
    com.strapdata.strapkop.reconcilier.DataCenterController dataCenterController;

    @Inject
    CqlKeyspaceManager cqlKeyspaceManager;

    @Inject
    CqlRoleManager cqlRoleManager;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    /*
    @Post(value = "/{namespace}/{cluster}/{datacenter}/rollback", produces = MediaType.APPLICATION_JSON)
    public HttpStatus rollback(String namespace, String cluster, String datacenter) throws ApiException {
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        if (checkPointCache.containsKey(dcKey)) {
            ClusterKey clusterKey = new ClusterKey(cluster, namespace);
            logger.info("Summit a configuration rollback for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            //workQueue.submit(clusterKey, dataCenterRollbackReconcilier.reconcile(dcKey));
            return HttpStatus.ACCEPTED;
        } else {
            logger.info("No restore point for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            return HttpStatus.NO_CONTENT;
        }
    }

    @Post(value = "/{namespace}/{cluster}/{datacenter}/reconcile", produces = MediaType.APPLICATION_JSON)
    public HttpStatus reconcile(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        logger.info("Force a configuration reconciliation for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
        checkPointCache.remove(dcKey); // clear the restorePoint to take the current value of DC CRD
        workQueue.submit(clusterKey, dataCenterController.reconcile(namespace, cluster, datacenter));
        return HttpStatus.ACCEPTED;
    }
    */

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_keyspace", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlKeyspace> managedKeyspaces(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        return cqlKeyspaceManager.get(namespace, cluster, datacenter);
    }

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_role", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlRole> managedRoles(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        return cqlRoleManager.get(namespace, cluster, datacenter);
    }

    /**
     * Use the fabric8 datacenter for webhook admission.
     * @param admissionReview
     * @return
     * @throws ApiException
     */
    @Post(value = "/validation", consumes = MediaType.APPLICATION_JSON)
    public Single<AdmissionReview> validate(@Body AdmissionReview admissionReview) throws ApiException {
        logger.warn("input admissionReview={}", admissionReview);

        if (admissionReview.getRequest().getName() == null) {
            // resource created.
            return Single.just(new AdmissionReviewBuilder()
                    .withResponse(new AdmissionResponseBuilder()
                            .withAllowed(true)
                            .withUid(admissionReview.getRequest().getUid()).build())
                    .build());
        }

        Key dcKey = new Key(admissionReview.getRequest().getName(), admissionReview.getRequest().getNamespace());
        return k8sResourceUtils.readDatacenter(dcKey)
                .map(dc -> {
                    com.strapdata.strapkop.model.fabric8.datacenter.DataCenter datacenter = (com.strapdata.strapkop.model.fabric8.datacenter.DataCenter) admissionReview.getRequest().getObject();

                    // Attempt to change the clusterName
                    if (!datacenter.getSpec().getClusterName().equals(dc.getSpec().getClusterName())) {
                        throw new IllegalArgumentException("Cannot change the cassandra cluster name");
                    }

                    // Attempt to change the datacenterName
                    if (!datacenter.getSpec().getDatacenterName().equals(dc.getSpec().getDatacenterName())) {
                        throw new IllegalArgumentException("Cannot change the cassandra datacenter name");
                    }

                    if (PodsAffinityPolicy.SLACK.equals(datacenter.getSpec().getPodsAffinityPolicy()) &&
                                    (datacenter.getSpec().getNetworking().getHostNetworkEnabled() ||
                                    datacenter.getSpec().getNetworking().getHostPortEnabled())) {
                        throw new IllegalArgumentException("PodsAffinityPolicy cannot be SLACK when hostNetwork or hostPort is true, this would cause a TCP port conflict.");
                    }

                    logger.debug("Accept datacenter={}", datacenter);
                    return new AdmissionReviewBuilder()
                            .withResponse(new AdmissionResponseBuilder()
                                    .withAllowed(true)
                                    .withUid(admissionReview.getRequest().getUid()).build())
                            .build();
                })
                .onErrorReturn(t -> {
                    logger.warn("Invalid datacenter key=" + dcKey, t);
                    Status status = new Status();
                    status.setCode(400);
                    status.setMessage(t.getMessage());
                    return new AdmissionReviewBuilder()
                            .withResponse(new AdmissionResponseBuilder()
                                    .withAllowed(false)
                                    .withStatus(status)
                                    .withUid(admissionReview.getRequest().getUid()).build())
                            .build();
                });
    }
}