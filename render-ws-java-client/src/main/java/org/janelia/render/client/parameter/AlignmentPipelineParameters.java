package org.janelia.render.client.parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.util.FileUtil;

/**
 * Parameters for an alignment pipeline run.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineParameters
        implements Serializable {

    private final MultiProjectParameters multiProject;
    private final MipmapParameters mipmap;
    private final List<MatchRunParameters> matchRunList;
    private final MFOVMontageMatchPatchParameters mfovMontagePatch;
    private final UnconnectedCrossMFOVParameters unconnectedCrossMfov;
    private final TileClusterParameters tileCluster;
    private final List<MatchCopyParameters> matchCopyList;

    @SuppressWarnings("unused")
    public AlignmentPipelineParameters() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null);
    }

    public AlignmentPipelineParameters(final MultiProjectParameters multiProject,
                                       final MipmapParameters mipmap,
                                       final List<MatchRunParameters> matchRunList,
                                       final MFOVMontageMatchPatchParameters mfovMontagePatch,
                                       final UnconnectedCrossMFOVParameters unconnectedCrossMfov,
                                       final TileClusterParameters tileCluster,
                                       final List<MatchCopyParameters> matchCopyList) {
        this.multiProject = multiProject;
        this.mipmap = mipmap;
        this.matchRunList = matchRunList;
        this.mfovMontagePatch = mfovMontagePatch;
        this.unconnectedCrossMfov = unconnectedCrossMfov;
        this.tileCluster = tileCluster;
        this.matchCopyList = matchCopyList;
    }

    public MultiProjectParameters getMultiProject() {
        return multiProject;
    }

    public boolean hasMipmapParameters() {
        return mipmap != null;
    }

    public MipmapParameters getMipmap() {
        return mipmap;
    }

    public boolean hasMatchParameters() {
        return (matchRunList != null) && (matchRunList.size() > 0);
    }

    public List<MatchRunParameters> getMatchRunList() {
        return matchRunList;
    }

    public boolean hasMfovMontagePatchParameters() {
        return (mfovMontagePatch != null);
    }

    public MFOVMontageMatchPatchParameters getMfovMontagePatch() {
        return mfovMontagePatch;
    }

    public boolean hasUnconnectedCrossMfovParameters() {
        return (unconnectedCrossMfov != null);
    }

    public UnconnectedCrossMFOVParameters getUnconnectedCrossMfov() {
        return unconnectedCrossMfov;
    }

    public boolean hasTileClusterParameters() {
        return (tileCluster != null);
    }

    public TileClusterParameters getTileCluster() {
        return tileCluster;
    }

    public boolean hasMatchCopyParameters() {
        return (matchCopyList != null) && (matchCopyList.size() > 0);
    }

    public List<MatchCopyParameters> getMatchCopyList() {
        return matchCopyList;
    }

    @SuppressWarnings("unused")
    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static AlignmentPipelineParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static AlignmentPipelineParameters fromJsonFile(final String dataFile)
            throws IOException {
        final AlignmentPipelineParameters parameters;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            parameters = fromJson(reader);
        }
        return parameters;
    }

    private static final JsonUtils.Helper<AlignmentPipelineParameters> JSON_HELPER =
            new JsonUtils.Helper<>(AlignmentPipelineParameters.class);
}