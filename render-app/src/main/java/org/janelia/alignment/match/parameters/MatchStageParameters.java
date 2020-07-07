package org.janelia.alignment.match.parameters;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;

/**
 * Match parameters for one stage of a multi-stage (pass) process.
 *
 * @author Eric Trautman
 */
public class MatchStageParameters
        implements Serializable {

    private final String stageName;
    private final FeatureRenderParameters featureRender;
    private final FeatureRenderClipParameters featureRenderClip;
    private final FeatureExtractionParameters featureExtraction;
    private final MatchDerivationParameters featureMatchDerivation;
    private final GeometricDescriptorAndMatchFilterParameters geometricDescriptorAndMatch;
    private final Double maxNeighborDistance;

    @SuppressWarnings("unused")
    public MatchStageParameters() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null);
    }

    public MatchStageParameters(final String stageName,
                                final FeatureRenderParameters featureRender,
                                final FeatureRenderClipParameters featureRenderClip,
                                final FeatureExtractionParameters featureExtraction,
                                final MatchDerivationParameters featureMatchDerivation,
                                final GeometricDescriptorAndMatchFilterParameters geometricDescriptorAndMatch,
                                final Double maxNeighborDistance) {
        this.stageName = stageName;
        this.featureRender = featureRender;
        this.featureRenderClip = featureRenderClip;
        this.featureExtraction = featureExtraction;
        this.featureMatchDerivation = featureMatchDerivation;
        this.geometricDescriptorAndMatch = geometricDescriptorAndMatch;
        this.maxNeighborDistance = maxNeighborDistance;
    }

    public String getStageName() {
        return stageName;
    }

    public FeatureRenderParameters getFeatureRender() {
        return featureRender;
    }

    public FeatureRenderClipParameters getFeatureRenderClip() {
        return featureRenderClip;
    }

    public FeatureExtractionParameters getFeatureExtraction() {
        return featureExtraction;
    }

    public MatchDerivationParameters getFeatureMatchDerivation() {
        return featureMatchDerivation;
    }

    public boolean hasGeometricDescriptorAndMatchFilterParameters() {
        return geometricDescriptorAndMatch != null;
    }

    public GeometricDescriptorAndMatchFilterParameters getGeometricDescriptorAndMatch() {
        return geometricDescriptorAndMatch;
    }

    public boolean exceedsMaxNeighborDistance(final Double absoluteDeltaZ) {
        return (maxNeighborDistance != null) && (absoluteDeltaZ != null) && (absoluteDeltaZ > maxNeighborDistance);
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {

        if (featureExtraction == null) {
            throw new IllegalArgumentException("siftFeatureParameters must be defined");
        } else {
            featureExtraction.setDefaults();
        }

        if (featureMatchDerivation == null) {
            throw new IllegalArgumentException("featureMatchDerivationParameters must be defined");
        } else {
            featureMatchDerivation.setDefaults();
        }

        if (geometricDescriptorAndMatch != null) {
            geometricDescriptorAndMatch.validateAndSetDefaults();
        }
    }

    public static MatchStageParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<MatchStageParameters> fromJsonArray(final Reader json) {
        return JSON_HELPER.fromJsonArray(json);
    }

    public static List<MatchStageParameters> fromJsonArrayFile(final String dataFile)
            throws IOException {
        final List<MatchStageParameters> stageParametersList;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            stageParametersList = fromJsonArray(reader);
        }
        return stageParametersList;
    }

    private static final JsonUtils.Helper<MatchStageParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MatchStageParameters.class);

}
