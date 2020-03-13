package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractPlugin implements Plugin {
    final static Logger logger = LoggerFactory.getLogger(AbstractPlugin.class);

    final ApplicationContext context;
    final K8sResourceUtils k8sResourceUtils;
    final AuthorityManager authorityManager;
    final CoreV1Api coreApi;
    final AppsV1Api appsApi;
    final OperatorConfig operatorConfig;
    final MeterRegistry meterRegistry;

    public AbstractPlugin(final ApplicationContext context,
                          K8sResourceUtils k8sResourceUtils,
                          AuthorityManager authorityManager,
                          CoreV1Api coreApi,
                          AppsV1Api appsApi,
                          OperatorConfig operatorConfig,
                          MeterRegistry meterRegistry) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.operatorConfig = operatorConfig;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Call when the datacenter is reconcilied after a start or scale up/downn
     *
     * @param dataCenter
     */
    @Override
    public Completable reconciled(DataCenter dataCenter) throws ApiException, StrapkopException {
        return Completable.complete();
    }
}
