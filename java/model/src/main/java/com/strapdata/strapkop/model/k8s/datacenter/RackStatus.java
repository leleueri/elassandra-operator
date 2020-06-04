package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class RackStatus {

    /**
     * Rack name (or availability zone name)
     */
    @JsonPropertyDescription("Rack name (or availability zone name)")
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Rack index starting at 0 (Build form the DataCenterStatus.zones)
     */
    @JsonPropertyDescription("Rack index starting at 0")
    @SerializedName("index")
    @Expose
    private Integer index;

    /**
     * Current DC heath
     */
    @JsonPropertyDescription("Current DC heath")
    @SerializedName("health")
    @Expose
    private Health health = Health.UNKNOWN;

    /**
     * Datacenter spec and user configmap fingerprint
     */
    @JsonPropertyDescription("Datacenter spec and user configmap fingerprint")
    @SerializedName("fingerprint")
    @Expose
    private String fingerprint = null;

    /**
     * Number of replica desired in the underlying sts.
     */
    @JsonPropertyDescription("Number of replica desired in the underlying StatefulSet")
    @SerializedName("desiredReplicas")
    @Expose
    private Integer desiredReplicas = 0;

    /**
     * Number of replica ready in the underlying sts.
     */
    @JsonPropertyDescription("Number of replica ready in the underlying StatefulSet")
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    public Health health() {
        if (readyReplicas != null && desiredReplicas != null && desiredReplicas == readyReplicas)
            return Health.GREEN;
        if (readyReplicas != null && readyReplicas > 0 && desiredReplicas != null && desiredReplicas - readyReplicas == 1)
            return Health.YELLOW;
        return Health.RED;
    }
}
