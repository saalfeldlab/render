package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.jspecify.annotations.NonNull;

/**
 * Transfer information about a volume.
 *
 * @author Eric Trautman
 */
public record VolumeTransferInfo(@JsonGetter(value = "scope_data_set") ScopeDataSet scopeDataSet,
                                 @JsonGetter(value = "cluster_root_paths") ClusterRootPaths clusterRootPaths,
                                 @JsonGetter(value = "max_mipmap_level") Integer maxMipmapLevel,
                                 @JsonGetter(value = "render_data_set") RenderDataSet renderDataSet,
                                 @JsonGetter(value = "transfer_tasks") List<TransferTask> transferTasks)
        implements Serializable {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public VolumeTransferInfo() {
        this(null, null, null, null, null);
    }

    public boolean hasApplyFibsemCorrectionTransformTask() {
        return transferTasks != null && transferTasks.contains(TransferTask.APPLY_FIBSEM_CORRECTION_TRANSFORM);
    }

    public boolean hasExportPreviewVolumeTask() {
        return transferTasks != null && transferTasks.contains(TransferTask.EXPORT_PREVIEW_VOLUME);
    }

    @Override
    public @NonNull String toString() {
        return JSON_HELPER.toJson(this);
    }

    public static VolumeTransferInfo fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static VolumeTransferInfo fromJson(final Reader reader) {
        return JSON_HELPER.fromJson(reader);
    }

    public static VolumeTransferInfo fromJsonFile(final String dataFile)
            throws IOException {
        final VolumeTransferInfo volumeTransferInfo;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            volumeTransferInfo = fromJson(reader);
        }
        return volumeTransferInfo;
    }

    private static final JsonUtils.Helper<VolumeTransferInfo> JSON_HELPER =
            new JsonUtils.Helper<>(VolumeTransferInfo.class);
}
