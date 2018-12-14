package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for analyzing existing match data to identify poor quality tile pairs.
 *
 * @author Eric Trautman
 */
public class FindPoorTilePairsClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--stack", description = "Stack name", required = true)
        public String stack;

        @Parameter(names =
                "--baseOwner",
                description = "Name of base/parent owner from which the render stack was derived (default assumes same owner as render stack)"
        )
        private String baseOwner;

        @Parameter(names =
                "--baseProject",
                description = "Name of base/parent project from which the render stack was derived (default assumes same project as render stack)"
        )
        private String baseProject;

        @Parameter(names =
                "--baseStack",
                description = "Name of base/parent stack from which the render stack was derived (default assumes same as render stack)"
        )
        private String baseStack;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--z",
                description = "Explicit z values for layers to be processed (only valid for generating montage pairs with --zNeighborDistance 0)",
                variableArity = true) // e.g. --z 20.0 --z 21.0 --z 22.0
        public List<Double> zValues;

        @Parameter(
                names = "--matchCollection",
                description = "Name of match collection to analyze",
                required = true
        )
        public String matchCollection;

        @Parameter(
                names = "--matchOwner",
                description = "Owner of match collection to analyze (default is owner)"
        )
        public String matchOwner;

        @Parameter(
                names = "--pairMaxDeltaStandardDeviation",
                description = "Save match pairs with delta x or delta y standard deviations greater than this number"
        )
        public Double pairMaxDeltaStandardDeviation = 8.0;

        @Parameter(
                names = "--toJson",
                description = "JSON file where tile pairs are to be stored (.json, .gz, or .zip)",
                required = true)
        public String toJson;

        @ParametersDelegate
        public LayerBoundsParameters bounds = new LayerBoundsParameters();

        public Parameters() {
        }

        String getBaseOwner() {
            if (baseOwner == null) {
                baseOwner = renderWeb.owner;
            }
            return baseOwner;
        }

        String getBaseProject() {
            if (baseProject == null) {
                baseProject = renderWeb.project;
            }
            return baseProject;
        }

        String getBaseStack() {
            if (baseStack == null) {
                baseStack = stack;
            }
            return baseStack;
        }

        String getMatchOwner() {
            if (matchOwner == null) {
                matchOwner = renderWeb.owner;
            }
            return matchOwner;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.bounds.validate();

                File toFile = new File(parameters.toJson).getAbsoluteFile();
                if (! toFile.exists()) {
                    toFile = toFile.getParentFile();
                }

                if (! toFile.canWrite()) {
                    throw new IllegalArgumentException("cannot write to " + toFile.getAbsolutePath());
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final FindPoorTilePairsClient client = new FindPoorTilePairsClient(parameters);

                client.findAndSavePoorQualityMatchPairs();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private FindPoorTilePairsClient(final Parameters parameters) throws IllegalArgumentException {

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private String getRenderParametersUrlTemplate() {
        final RenderWebServiceUrls urls = new RenderWebServiceUrls(parameters.renderWeb.baseDataUrl,
                                                                   parameters.getBaseOwner(),
                                                                   parameters.getBaseProject());
        final String currentStackUrlString = urls.getStackUrlString(parameters.getBaseStack());
        final String relativeStackUrlString =
                currentStackUrlString.substring(parameters.renderWeb.baseDataUrl.length());
        return RenderableCanvasIdPairs.TEMPLATE_BASE_DATA_URL_TOKEN + relativeStackUrlString +
               "/tile/" + RenderableCanvasIdPairs. TEMPLATE_ID_TOKEN + "/render-parameters";
    }

    private void findAndSavePoorQualityMatchPairs()
            throws IllegalArgumentException, IOException {

        LOG.info("findAndSavePoorQualityMatchPairs: entry");

        final String renderParametersUrlTemplate = getRenderParametersUrlTemplate();

        final List<SectionData> sectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                       parameters.layerRange.minZ,
                                                                                       parameters.layerRange.maxZ,
                                                                                       parameters.zValues);
        final Map<String, Double> sectionIdToZMap = new TreeMap<>();
        sectionDataList.forEach(sectionData -> sectionIdToZMap.put(sectionData.getSectionId(), sectionData.getZ()));


        if (sectionIdToZMap.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any sections with the specified z values");
        }

        final String collectionName = parameters.matchCollection;
        final RenderDataClient matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                      parameters.getMatchOwner(),
                                                                      collectionName);

        final Set<OrderedCanvasIdPair> poorNeighborPairs = new TreeSet<>();

        Double z = null;
        final Map<String, TileBounds> tileIdToBoundsMap = new HashMap<>();
        for (final String sectionId : sectionIdToZMap.keySet()) {

            final int startSize = poorNeighborPairs.size();

            if ((z == null) || (! z.equals(sectionIdToZMap.get(sectionId)))) {
                z = sectionIdToZMap.get(sectionId);
                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
                tileIdToBoundsMap.clear();
                tileBoundsList.forEach(tileBounds -> tileIdToBoundsMap.put(tileBounds.getTileId(), tileBounds));
            }

            final List<CanvasMatches> canvasMatchesList = matchDataClient.getMatchesWithinGroup(sectionId);
            canvasMatchesList.forEach(cm -> {

                final Matches m = cm.getMatches();
                final double dxStd = m.calculateStandardDeviationForDeltaX();
                final double dyStd = m.calculateStandardDeviationForDeltaY();
                if ((dxStd > parameters.pairMaxDeltaStandardDeviation) ||
                    (dyStd > parameters.pairMaxDeltaStandardDeviation)) {

                    final TileBounds fromTile = tileIdToBoundsMap.get(cm.getOriginalPId());
                    final List<TileBounds> toTiles =
                            Collections.singletonList(tileIdToBoundsMap.get(cm.getOriginalQId()));
                    poorNeighborPairs.addAll(
                            TileBoundsRTree.getDistinctPairs(fromTile,
                                                             toTiles,
                                                             true,
                                                             false,
                                                             true));
                }

            });

            final int numberOfPoorPairs = poorNeighborPairs.size() - startSize;

            LOG.info("found {} out of {} pairs with poor quality matches for section {}",
                     numberOfPoorPairs, canvasMatchesList.size(), sectionId);
        }

        if (poorNeighborPairs.size() > 0) {
            final RenderableCanvasIdPairs renderableCanvasIdPairs =
                    new RenderableCanvasIdPairs(renderParametersUrlTemplate,
                                                new ArrayList<>(poorNeighborPairs));
            FileUtil.saveJsonFile(parameters.toJson, renderableCanvasIdPairs);
        }

        LOG.info("findAndSavePoorQualityMatchPairs: exit, saved {} total pairs", poorNeighborPairs.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(FindPoorTilePairsClient.class);

}
