package com.strapdata.strapkop.watch;

import com.instaclustr.model.backup.BackupList;
import com.instaclustr.model.k8s.backup.Backup;
import com.squareup.okhttp.Call;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import javax.inject.Named;
import java.util.Collection;

@Context
public class BackupWatchService extends WatchService<Backup, BackupList> {
    private final CustomObjectsApi customObjectsApi;
    private final String namespace;

    public BackupWatchService(final ApiClient apiClient,
                              final CustomObjectsApi customObjectsApi,
                              @Named("namespace") final String namespace) {
        super(apiClient);
        this.customObjectsApi = customObjectsApi;
        this.namespace = namespace;
    }

    @Override
    protected Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException {
        return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1", namespace, "elassandra-backups", null, null, resourceVersion, watch, null, null);
    }

    @Override
    protected Collection<? extends Backup> resourceListItems(final BackupList backupList) {
        return backupList.getItems();
    }

    @Override
    protected V1ListMeta resourceListMetadata(final BackupList backupList) {
        return backupList.getMetadata();
    }

    @Override
    protected V1ObjectMeta resourceMetadata(final Backup backup) {
        return backup.getMetadata();
    }
}