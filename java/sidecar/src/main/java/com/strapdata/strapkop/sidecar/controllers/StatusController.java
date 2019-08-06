package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusController {
    
    private static final Logger logger = LoggerFactory.getLogger(StatusController.class);
    
    private final StorageServiceMBean storageServiceMBean;

    public StatusController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Get("/")
    public NodeStatus getStatus() {
        try {
            return NodeStatus.valueOf(storageServiceMBean.getOperationMode());
        }
        catch (RuntimeException e) {
            logger.error("error while getting operation mode", e);
            return NodeStatus.UNKNOWN;
        }
    }
}
