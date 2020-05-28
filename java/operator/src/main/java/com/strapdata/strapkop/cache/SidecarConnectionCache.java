package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.sidecar.SidecarClient;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;

/**
 * This cache associate a sidecar client to an elassandra pod.
 */
@Singleton
public class SidecarConnectionCache extends Cache<ElassandraPod, SidecarClient> {

    private static final Logger logger = LoggerFactory.getLogger(SidecarConnectionCache.class);

    SidecarConnectionCache(MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "sidecar_client")), this);
    }

    /**
     * Remove all clients that match a given datacenter. Client are closed before removal
     */
    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e -> {
                    if (Objects.equals(e.getKey().getParent(), dc.getMetadata().getName()) &&
                            Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace())) {
                        try {
                            e.getValue().close();
                        }
                        catch (RuntimeException exc) {
                            logger.warn("runtime error while closing sidecar client for pod={}", e.getKey().getName(), exc);
                        }
                        return true;
                    } else {
                        return false;
                    }
                }
        );
    }
}
