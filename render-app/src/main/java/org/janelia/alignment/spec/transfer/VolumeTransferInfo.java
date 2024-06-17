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

/**
 * Transfer information about a volume.
 *
 * @author Eric Trautman
 */
public class VolumeTransferInfo implements Serializable {

    private final ScopeDataSet scopeDataSet;
    private final ClusterRootPaths clusterRootPaths;
    private final Integer maxMipmapLevel;
    private final RenderDataSet renderDataSet;
    private final List<TransferTask> transferTasks;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public VolumeTransferInfo() {
        this(null, null, null, null, null);
    }

    public VolumeTransferInfo(final ScopeDataSet scopeDataSet,
                              final ClusterRootPaths clusterRootPaths,
                              final Integer maxMipmapLevel,
                              final RenderDataSet renderDataSet,
                              final List<TransferTask> transferTasks) {
        this.scopeDataSet = scopeDataSet;
        this.clusterRootPaths = clusterRootPaths;
        this.maxMipmapLevel = maxMipmapLevel;
        this.renderDataSet = renderDataSet;
        this.transferTasks = transferTasks;
    }

    @JsonGetter(value = "scope_data_set")
    public ScopeDataSet getScopeDataSet() {
        return scopeDataSet;
    }

    @JsonGetter(value = "cluster_root_paths")
    public ClusterRootPaths getClusterRootPaths() {
        return clusterRootPaths;
    }

    @JsonGetter(value = "max_mipmap_level")
    public Integer getMaxMipmapLevel() {
        return maxMipmapLevel;
    }

    @JsonGetter(value = "render_data_set")
    public RenderDataSet getRenderDataSet() {
        return renderDataSet;
    }

    @JsonGetter(value = "transfer_tasks")
    public List<TransferTask> getTransferTasks() {
        return transferTasks;
    }

    public boolean hasApplyFibsemCorrectionTransformTask() {
        return transferTasks != null && transferTasks.contains(TransferTask.APPLY_FIBSEM_CORRECTION_TRANSFORM);
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
