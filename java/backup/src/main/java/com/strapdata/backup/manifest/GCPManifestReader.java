package com.strapdata.backup.manifest;

import com.google.cloud.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.strapdata.backup.common.Constants;
import com.strapdata.backup.common.GCPRemoteObjectReference;
import com.strapdata.backup.common.RemoteObjectReference;
import com.strapdata.backup.downloader.GCPDownloader;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

public class GCPManifestReader extends ManifestReader {
    private static final Logger logger = LoggerFactory.getLogger(GCPDownloader.class);

    private final Storage storage;

    public GCPManifestReader(Storage storage, final String rootBackupDir, String restoreFromClusterId, String restoreFromBackupBucket) {
        super(rootBackupDir, restoreFromClusterId, restoreFromBackupBucket);
        this.storage = storage;
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(Path objectKey) throws Exception {
        return new GCPRemoteObjectReference(objectKey, resolveRemotePath(objectKey), restoreFromBackupBucket);
    }

    @Override
    public RemoteObjectReference taskDescriptionRemoteReference(String taskName) throws Exception {
        // objectKey is kept simple (e.g. "manifests/autosnap-123") so that it directly reflects the local path
        return new GCPRemoteObjectReference(Paths.get(Constants.TASK_DESCRIPTION_DOWNLOAD_DIR).resolve(taskName), resolveTaskDescriptionRemotePath(taskName), restoreFromBackupBucket);
    }

    public GlobalManifest aggregateManifest(final String manifestName) {
        Storage.BlobListOption currentDir = Storage.BlobListOption.currentDirectory();
        Storage.BlobListOption prefix = Storage.BlobListOption.prefix(getPrefix());
        Storage.BlobListOption size = Storage.BlobListOption.pageSize(50);

        Page<Blob> p = storage.list(this.restoreFromBackupBucket, currentDir, prefix, size);
        Iterator<Blob> blobIterator = p.iterateAll();

        return Observable.fromIterable(() -> blobIterator).observeOn(Schedulers.io())
                .doOnNext(blob -> logger.debug("AggregateManifest found nodePath '{}'", blob.getName()))
                .map(blob -> {
                    final String nodePath = blob.getName();

                    final String nodeManifestPath = nodePath + "manifests/" + manifestName;
                    Blob b = storage.get(this.restoreFromBackupBucket, nodeManifestPath);
                    if (b != null && b.exists()) {
                        logger.debug("Manifest '{}' found for nodePath '{}'", manifestName, nodePath);
                        return new Tuple2<String, String>(nodePath.split("/")[1], "manifests/" + manifestName);
                    } else {
                        logger.debug("Manifest '{}' not found for nodePath '{}'", manifestName, nodePath);
                        return new Tuple2<String, String>(nodePath.split("/")[1], NOMANIFEST);
                    }
                })
                .filter(tuple -> !NOMANIFEST.equals(tuple._2)).collect(
                () -> new GlobalManifest(this.restoreFromClusterId, manifestName),
                (globalManifest, tuple) -> globalManifest.addManifest(tuple._1, tuple._2)
        ).blockingGet();
    }
}
