package org.janelia.alignment.match.parameters;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
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

    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    /**
     * Builds a <a href="https://en.wikipedia.org/wiki/Slug_(publishing)">slug</a> string for
     * these stage parameters with the form:
     * <pre>
     *     [stage name]_
     *     SIFT_s[render scale]e[max epsilon]_i[min inliers]_c[coverage]pct_
     *     GEO_s[render scale]e[max epsilon]_i[min combined inliers]_c[combined coverage]pct_
     *     d[max neighbor distance]_h[parameters hash]
     *
     *     e.g. crossPass3_SIFT_s0.15e09_i020_c050pct_GEO_s0.25e04_i150_c050pct_d006_h1588887f682e680539e3654936b5dac3
     * </pre>
     *
     * @return string that summarizes the primary parameter values for this stage.
     */
    public String toSlug() {

        final String siftSlug = getMethodSlug("SIFT",
                                              featureRender.renderScale,
                                              featureMatchDerivation.getMatchMaxEpsilonForRenderScale(featureRender.renderScale),
                                              featureMatchDerivation.matchMinNumInliers,
                                              featureMatchDerivation.matchMinCoveragePercentage);

        String geoSlug = "GEO_none----_----_-------";
        if ((geometricDescriptorAndMatch != null) &&
            (geometricDescriptorAndMatch.gdEnabled) &&
            (geometricDescriptorAndMatch.matchDerivationParameters != null)) {
            geoSlug = getMethodSlug("GEO",
                                    geometricDescriptorAndMatch.renderScale,
                                    geometricDescriptorAndMatch.matchDerivationParameters.getMatchMaxEpsilonForRenderScale(geometricDescriptorAndMatch.renderScale),
                                    geometricDescriptorAndMatch.minCombinedInliers,
                                    geometricDescriptorAndMatch.minCombinedCoveragePercentage);
        }

        final int dist = getDefinedInt(maxNeighborDistance);
        final String md5Hex = DigestUtils.md5Hex(this.toJson());

        return stageName + "_" + siftSlug + "_" + geoSlug + String.format("_d%03d_h%s", dist, md5Hex);
    }

    private int getDefinedInt(final Number number) {
        return number == null ? 0 : number.intValue();
    }

    private String getMethodSlug(final String method,
                                 final Double renderScale,
                                 final Float matchMaxEpsilon,
                                 final Integer matchMinNumInliers,
                                 final Double matchMinCoveragePercentage) {
        return String.format("%s_s%4.2fe%02d_i%03d_c%03dpct",
                             method,
                             renderScale == null ? 0.0 : renderScale,
                             getDefinedInt(matchMaxEpsilon),
                             getDefinedInt(matchMinNumInliers),
                             getDefinedInt(matchMinCoveragePercentage));
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
