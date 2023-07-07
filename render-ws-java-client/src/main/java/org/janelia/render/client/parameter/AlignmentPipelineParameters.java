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

    @SuppressWarnings("unused")
    public AlignmentPipelineParameters() {
        this(null, null, null);
    }

    public AlignmentPipelineParameters(final MultiProjectParameters multiProject,
                                       final MipmapParameters mipmap,
                                       final List<MatchRunParameters> matchRunList) {
        this.multiProject = multiProject;
        this.mipmap = mipmap;
        this.matchRunList = matchRunList;
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