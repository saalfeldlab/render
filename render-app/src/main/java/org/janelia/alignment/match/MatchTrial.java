package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.FeatureAndMatchParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.match.stage.MultiStageMatcher;
import org.janelia.alignment.match.stage.StageMatcher;
import org.janelia.alignment.match.stage.StageMatchingResources;
import org.janelia.alignment.match.stage.StageMatchingStats;
import org.janelia.alignment.util.ImageProcessorCache;

import static org.janelia.alignment.match.RenderableCanvasIdPairs.TEMPLATE_ID_TOKEN;

/**
 * Encapsulates all information for a match trial.
 *
 * @author Eric Trautman
 */
public class MatchTrial implements Serializable {

    private String id;
    private final MatchTrialParameters parameters;
    private List<Matches> matches;
    private StageMatchingStats stats;
    private StageMatchingStats gdStats;

    @SuppressWarnings("unused")
    MatchTrial() {
        this(null);
    }

    public MatchTrial(final MatchTrialParameters parameters) {
        this.parameters = parameters;
    }

    public String getId() {
        return id;
    }

    public MatchTrialParameters getParameters() {
        return parameters;
    }

    public List<Matches> getMatches() {
        return matches;
    }

    public void setMatches(final List<Matches> matches) {
        this.matches = matches;
    }

    public StageMatchingStats getStats() {
        return stats;
    }

    public void setStats(final StageMatchingStats stats) {
        this.stats = stats;
    }

    public MatchTrial getCopyWithId(final String id) {
        final MatchTrial copy = new MatchTrial(parameters);
        copy.id = id;
        copy.matches = this.matches;
        copy.stats = this.stats;
        copy.gdStats = this.gdStats;
        return copy;
    }

    public void deriveResults(final ImageProcessorCache sourceImageProcessorCache)
            throws IllegalArgumentException {

        final FeatureAndMatchParameters featureAndMatchParameters = parameters.getFeatureAndMatchParameters();
        final FeatureRenderClipParameters clipParameters = featureAndMatchParameters.getClipParameters();
        final GeometricDescriptorAndMatchFilterParameters gdmfParameters =
                parameters.getGeometricDescriptorAndMatchFilterParameters();

        MontageRelativePosition pClipPosition = null;
        MontageRelativePosition qClipPosition = null;

        if ((clipParameters != null) &&
            ((clipParameters.clipWidth != null) || (clipParameters.clipHeight != null))) {
            pClipPosition = featureAndMatchParameters.getpClipPosition();
            qClipPosition = pClipPosition.getOpposite();
        }

        final Double maxNeighborDistance = null;
        final MatchStageParameters matchStageParameters =
                new MatchStageParameters("matchTrial",
                                         FeatureRenderParameters.fromUrl(parameters.getpRenderParametersUrl()),
                                         featureAndMatchParameters.getClipParameters(),
                                         featureAndMatchParameters.getSiftFeatureParameters(),
                                         featureAndMatchParameters.getMatchDerivationParameters(),
                                         gdmfParameters,
                                         maxNeighborDistance);

        final String urlTemplateString = getTemplateString(parameters.getpRenderParametersUrl());
        final FeatureStorageParameters featureStorageParameters = new FeatureStorageParameters();
        final StageMatchingResources stageResources = new StageMatchingResources(matchStageParameters,
                                                                                 urlTemplateString,
                                                                                 featureStorageParameters,
                                                                                 sourceImageProcessorCache,
                                                                                 null);

        final String groupId = "trial";
        final CanvasId p = new CanvasId(groupId, getTileId(parameters.getpRenderParametersUrl()), pClipPosition);
        final CanvasId q = new CanvasId(groupId, getTileId(parameters.getqRenderParametersUrl()), qClipPosition);
        final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(p, q, null);
        final StageMatcher stageMatcher = new StageMatcher(stageResources, true);
        final List<StageMatcher> stageMatcherList = Collections.singletonList(stageMatcher);
        final MultiStageMatcher multiStageMatcher = new MultiStageMatcher(stageMatcherList);

        final MultiStageMatcher.PairResult pairResult = multiStageMatcher.generateMatchesForPair(pair);
        final List<CanvasMatches> canvasMatchesList = pairResult.getCanvasMatchesList();

        this.matches = canvasMatchesList.stream().map(CanvasMatches::getMatches).collect(Collectors.toList());

        final List<StageMatcher.PairResult> stagePairResultList = pairResult.getStagePairResultList();
        final StageMatcher.PairResult lastStagePairResult = stagePairResultList.get(stagePairResultList.size() - 1);
        this.stats = lastStagePairResult.getSiftStats();
        this.gdStats = lastStagePairResult.getGdStats();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static MatchTrial fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<MatchTrial> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrial.class);

    private static Matcher getTileUrlMatcher(final String tileUrlString)
            throws IllegalArgumentException {
        final Matcher m = TILE_TEMPLATE_PATTERN.matcher(tileUrlString);
        if (m.matches()) {
            return m;
        } else {
            throw new IllegalArgumentException("tileUrlString '" + tileUrlString + "' does not match pattern");
        }
    }

    private static String getTemplateString(final String tileUrlString)
            throws IllegalArgumentException {
        final Matcher m = getTileUrlMatcher(tileUrlString);
        return m.group(1) + TEMPLATE_ID_TOKEN + m.group(3);
    }

    private static String getTileId(final String tileUrlString)
            throws IllegalArgumentException {
        final Matcher m = getTileUrlMatcher(tileUrlString);
        return m.group(2);
    }

    // "http://renderer-dev:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-24_090152_0-0-1.29351.0/render-parameters?filter=true&scale=0.05"
    private static final Pattern TILE_TEMPLATE_PATTERN =
            Pattern.compile("(.*/tile/)([^/]++)(/render-parameters).*");
}
