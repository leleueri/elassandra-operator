package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1Pod;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class PodCache extends Cache<Key, V1Pod> {

    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e ->
                Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.DATACENTER), dc.getSpec().getDatacenterName()) &&
                        Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.CLUSTER), dc.getSpec().getClusterName()) &&
                        Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace()));
    }
}
