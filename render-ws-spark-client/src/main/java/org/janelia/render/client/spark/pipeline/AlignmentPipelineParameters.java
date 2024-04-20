package org.janelia.render.client.spark.pipeline;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.spec.stack.PipelineStackIdNamingGroups;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MaskHackParameters;
import org.janelia.render.client.parameter.MatchCopyParameters;
import org.janelia.render.client.parameter.MipmapParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.parameter.UnconnectedCrossMFOVParameters;
import org.janelia.render.client.parameter.ZSpacingParameters;

import static org.janelia.alignment.json.JsonUtils.STRICT_MAPPER;


/**
 * Parameters for an alignment pipeline run.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineParameters
        implements Serializable {

    private final MultiProjectParameters multiProject;
    private final PipelineStackIdNamingGroups pipelineStackGroups;
    private final List<AlignmentPipelineStepId> pipelineSteps;
    private final MipmapParameters mipmap;
    private final List<MatchRunParameters> matchRunList;
    private final MFOVMontageMatchPatchParameters mfovMontagePatch;
    private final UnconnectedCrossMFOVParameters unconnectedCrossMfov;
    private final TileClusterParameters tileCluster;
    private final MatchCopyParameters matchCopy;
    private final AffineBlockSolverSetup affineBlockSolverSetup;
    private final IntensityCorrectionSetup intensityCorrectionSetup;
    private final ZSpacingParameters zSpacing;
    private final MaskHackParameters maskHack;

    @SuppressWarnings("unused")
    public AlignmentPipelineParameters() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null);
    }

    public AlignmentPipelineParameters(final MultiProjectParameters multiProject,
                                       final PipelineStackIdNamingGroups pipelineStackGroups,
                                       final List<AlignmentPipelineStepId> pipelineSteps,
                                       final MipmapParameters mipmap,
                                       final List<MatchRunParameters> matchRunList,
                                       final MFOVMontageMatchPatchParameters mfovMontagePatch,
                                       final UnconnectedCrossMFOVParameters unconnectedCrossMfov,
                                       final TileClusterParameters tileCluster,
                                       final MatchCopyParameters matchCopy,
                                       final AffineBlockSolverSetup affineBlockSolverSetup,
                                       final IntensityCorrectionSetup intensityCorrectionSetup,
                                       final ZSpacingParameters zSpacing,
                                       final MaskHackParameters maskHack) {
        this.multiProject = multiProject;
        this.pipelineStackGroups = pipelineStackGroups;
        this.pipelineSteps = pipelineSteps;
        this.mipmap = mipmap;
        this.matchRunList = matchRunList;
        this.mfovMontagePatch = mfovMontagePatch;
        this.unconnectedCrossMfov = unconnectedCrossMfov;
        this.tileCluster = tileCluster;
        this.matchCopy = matchCopy;
        this.affineBlockSolverSetup = affineBlockSolverSetup;
        this.intensityCorrectionSetup = intensityCorrectionSetup;
        this.zSpacing = zSpacing;
        this.maskHack = maskHack;
    }

    public MultiProjectParameters getMultiProject(final StackIdNamingGroup withNamingGroup) {
        multiProject.setNamingGroup(withNamingGroup);
        return multiProject;
    }

    public StackIdNamingGroup getRawNamingGroup() {
        return pipelineStackGroups == null ? null : pipelineStackGroups.getRaw();
    }

    public StackIdNamingGroup getAlignedNamingGroup() {
        return pipelineStackGroups == null ? null : pipelineStackGroups.getAligned();
    }

    public StackIdNamingGroup getIntensityCorrectedNamingGroup() {
        return pipelineStackGroups == null ? null : pipelineStackGroups.getIntensityCorrected();
    }

    public MipmapParameters getMipmap() {
        return mipmap;
    }

    public List<MatchRunParameters> getMatchRunList() {
        return matchRunList;
    }

    public MFOVMontageMatchPatchParameters getMfovMontagePatch() {
        return mfovMontagePatch;
    }

    public UnconnectedCrossMFOVParameters getUnconnectedCrossMfov() {
        return unconnectedCrossMfov;
    }

    public TileClusterParameters getTileCluster() {
        return tileCluster;
    }

    public MatchCopyParameters getMatchCopy() {
        return matchCopy;
    }

    public String getMatchCopyToCollectionSuffix() {
        return matchCopy == null ? "" : matchCopy.toCollectionSuffix;
    }

    public AffineBlockSolverSetup getAffineBlockSolverSetup() {
        return affineBlockSolverSetup;
    }

    public IntensityCorrectionSetup getIntensityCorrectionSetup() {
        return intensityCorrectionSetup;
    }

    public ZSpacingParameters getZSpacing() {
        return zSpacing;
    }

    public MaskHackParameters getMaskHack() {
        return maskHack;
    }

    /**
     * @return a list of clients for each specified pipeline step.
     *
     * @throws IllegalArgumentException
     *   if no steps are defined or if any of the parameters are invalid.
     */

    public List<AlignmentPipelineStep> buildStepClients()
            throws IllegalArgumentException {

        if ((pipelineSteps == null) || pipelineSteps.isEmpty()) {
            throw new IllegalArgumentException("no pipeline steps defined");
        }

        validateRequiredElementExists("multiProject", multiProject);

        return pipelineSteps.stream()
                            .map(AlignmentPipelineStepId::toStepClient)
                            .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unused")
    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return STRICT_JSON_HELPER.toJson(this);
    }

    public static void validateRequiredElementExists(final String elementName,
                                                     final Object element)
            throws IllegalArgumentException {
        if (element == null) {
            throw new IllegalArgumentException(elementName + " missing from pipeline parameters");
        }
    }

    public static AlignmentPipelineParameters fromJson(final Reader json) {
        return STRICT_JSON_HELPER.fromJson(json);
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

    private static final JsonUtils.Helper<AlignmentPipelineParameters> STRICT_JSON_HELPER =
            new JsonUtils.Helper<>(STRICT_MAPPER, AlignmentPipelineParameters.class);
}