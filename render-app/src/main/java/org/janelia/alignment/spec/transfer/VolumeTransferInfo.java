package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;

import org.janelia.alignment.json.JsonUtils;

/**
 * Transfer information about a volume.
 *
 * @author Eric Trautman
 */
public class VolumeTransferInfo {

    private final ScopeDataSet scopeDataSet;
    private final ClusterRootPaths clusterRootPaths;
    private final Integer maxMipmapLevel;
    private final RenderDataSet renderDataSet;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public VolumeTransferInfo() {
        this(null, null, null, null);
    }

    public VolumeTransferInfo(final ScopeDataSet scopeDataSet,
                              final ClusterRootPaths clusterRootPaths,
                              final Integer maxMipmapLevel,
                              final RenderDataSet renderDataSet) {
        this.scopeDataSet = scopeDataSet;
        this.clusterRootPaths = clusterRootPaths;
        this.maxMipmapLevel = maxMipmapLevel;
        this.renderDataSet = renderDataSet;
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

    public static VolumeTransferInfo fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<VolumeTransferInfo> JSON_HELPER =
            new JsonUtils.Helper<>(VolumeTransferInfo.class);
}
