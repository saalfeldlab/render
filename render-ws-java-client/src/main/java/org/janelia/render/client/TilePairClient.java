package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for calculating neighbor pairs for all tiles in a range of sections.
 *
 * @author Eric Trautman
 */
public class TilePairClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names =
                "--baseOwner",
                description = "Name of base/parent owner from which the render stack was derived (default assumes same owner as render stack)",
                required = false)
        private String baseOwner;

        @Parameter(names =
                "--baseProject",
                description = "Name of base/parent project from which the render stack was derived (default assumes same project as render stack)",
                required = false)
        private String baseProject;

        @Parameter(names =
                "--baseStack",
                description = "Name of base/parent stack from which the render stack was derived (default assumes same as render stack)",
                required = false)
        private String baseStack;

        @Parameter(names = "--minZ", description = "Minimum Z value for all tiles", required = true)
        private Double minZ;

        @Parameter(names = "--maxZ", description = "Maximum Z value for all tiles", required = true)
        private Double maxZ;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles",
                required = false)
        private Double xyNeighborFactor = 0.9;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value",
                required = false)
        private Integer zNeighborDistance = 2;

        @Parameter(
                names = "--excludeCornerNeighbors",
                description = "Exclude neighbor tiles whose center x and y is outside the source tile's x and y range respectively",
                required = false,
                arity = 1)
        private boolean excludeCornerNeighbors = true;

        @Parameter(
                names = "--excludeCompletelyObscuredTiles",
                description = "Exclude tiles that are completely obscured by reacquired tiles",
                required = false,
                arity = 1)
        private boolean excludeCompletelyObscuredTiles = true;

        @Parameter(
                names = "--excludeSameLayerNeighbors",
                description = "Exclude neighbor tiles in the same layer (z) as the source tile",
                required = false,
                arity = 1)
        private boolean excludeSameLayerNeighbors = false;

        @Parameter(
                names = "--excludeSameSectionNeighbors",
                description = "Exclude neighbor tiles with the same sectionId as the source tile",
                required = false,
                arity = 1)
        private boolean excludeSameSectionNeighbors = false;

        @Parameter(
                names = "--excludePairsInMatchCollection",
                description = "Name of match collection whose existing pairs should be excluded from the generated list (default is to include all pairs)",
                required = false)
        private String excludePairsInMatchCollection;

        @Parameter(names = "--toJson", description = "JSON file where tile pairs are to be stored (.json, .gz, or .zip)", required = true)
        private String toJson;

        @Parameter(names = "--minX", description = "Minimum X value for all tiles", required = false)
        private Double minX;

        @Parameter(names = "--maxX", description = "Maximum X value for all tiles", required = false)
        private Double maxX;

        @Parameter(names = "--minY", description = "Minimum Y value for all tiles", required = false)
        private Double minY;

        @Parameter(names = "--maxY", description = "Maximum Y value for all tiles", required = false)
        private Double maxY;

        public Parameters() {
        }

        public Parameters(final String baseDataUrl,
                          final String owner,
                          final String project,
                          final String stack,
                          final Double minZ,
                          final Double maxZ,
                          final Double xyNeighborFactor,
                          final Integer zNeighborDistance,
                          final Double minX,
                          final Double maxX,
                          final Double minY,
                          final Double maxY) {
            this.baseDataUrl = baseDataUrl;
            this.owner = owner;
            this.project = project;
            this.stack = stack;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.xyNeighborFactor = xyNeighborFactor;
            this.zNeighborDistance = zNeighborDistance;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        public String getBaseOwner() {
            if (baseOwner == null) {
                baseOwner = owner;
            }
            return baseOwner;
        }

        public String getBaseProject() {
            if (baseProject == null) {
                baseProject = project;
            }
            return baseProject;
        }

        public String getBaseStack() {
            if (baseStack == null) {
                baseStack = stack;
            }
            return baseStack;
        }

        public void validateStackBounds() throws IllegalArgumentException {

            if (minZ > maxZ) {
                throw new IllegalArgumentException("minZ (" + minZ + ") is greater than maxX (" + maxZ + ")");
            }

            if ((minX != null) || (maxX != null) || (minY != null) || (maxY != null)) {

                if ((minX == null) || (maxX == null) || (minY == null) || (maxY == null)) {
                    throw new IllegalArgumentException("since one or more of minX (" + minX + "), maxX (" + maxX +
                                                       "), minY (" + minY + "), maxY (" + maxY +
                                                       ") is specified, all must be specified");
                }

                if (minX > maxX) {
                    throw new IllegalArgumentException("minX (" + minX + ") is greater than maxX (" + maxX + ")");
                }

                if (minY > maxY) {
                    throw new IllegalArgumentException("minY (" + minY + ") is greater than maxY (" + maxY + ")");
                }
            }

        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, TilePairClient.class);

                File toFile = new File(parameters.toJson).getAbsoluteFile();
                if (! toFile.exists()) {
                    toFile = toFile.getParentFile();
                }

                if (! toFile.canWrite()) {
                    throw new IllegalArgumentException("cannot write to " + toFile.getAbsolutePath());
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final TilePairClient client = new TilePairClient(parameters);

                // TODO: consider splitting up into multiple pair files for large z ranges

                final List<OrderedCanvasIdPair> neighborPairs = client.getSortedNeighborPairs();
                final RenderableCanvasIdPairs renderableCanvasIdPairs =
                        new RenderableCanvasIdPairs(client.getRenderParametersUrlTemplate(),
                                                    neighborPairs);
                FileUtil.saveJsonFile(parameters.toJson, renderableCanvasIdPairs);

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final boolean filterTilesWithBox;
    private final RenderDataClient renderDataClient;

    public TilePairClient(final Parameters parameters) throws IllegalArgumentException {

        parameters.validateStackBounds();

        this.parameters = parameters;
        this.filterTilesWithBox = (parameters.minX != null);

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);
    }

    public String getRenderParametersUrlTemplate() {
        final RenderWebServiceUrls urls = new RenderWebServiceUrls(parameters.baseDataUrl,
                                                                   parameters.getBaseOwner(),
                                                                   parameters.getBaseProject());
        final String currentStackUrlString = urls.getStackUrlString(parameters.getBaseStack());
        final String relativeStackUrlString = currentStackUrlString.substring(parameters.baseDataUrl.length());
        return RenderableCanvasIdPairs.TEMPLATE_BASE_DATA_URL_TOKEN + relativeStackUrlString +
               "/tile/" + RenderableCanvasIdPairs. TEMPLATE_ID_TOKEN + "/render-parameters";
    }

    public List<OrderedCanvasIdPair> getSortedNeighborPairs()
            throws IOException, InterruptedException {

        LOG.info("getSortedNeighborPairs: entry");

        final List<Double> zValues = renderDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        final Map<Double, TileBoundsRTree> zToTreeMap = buildRTrees(zValues);

        final Set<OrderedCanvasIdPair> existingPairs = getExistingPairs();
        final Set<OrderedCanvasIdPair> neighborPairs = new TreeSet<>();

        Double z;
        Double neighborZ;
        TileBoundsRTree currentZTree;
        List<TileBoundsRTree> neighborTreeList;
        Set<OrderedCanvasIdPair> currentNeighborPairs;
        for (int zIndex = 0; zIndex < zValues.size(); zIndex++) {

            z = zValues.get(zIndex);
            currentZTree = zToTreeMap.get(z);

            neighborTreeList = new ArrayList<>();

            final double maxNeighborZ = Math.min(parameters.maxZ, z + parameters.zNeighborDistance);

            for (int neighborZIndex = zIndex + 1; neighborZIndex < zValues.size(); neighborZIndex++) {
                neighborZ = zValues.get(neighborZIndex);
                if (neighborZ > maxNeighborZ) {
                    break;
                }
                neighborTreeList.add(zToTreeMap.get(neighborZ));
            }

            currentNeighborPairs = currentZTree.getCircleNeighbors(neighborTreeList,
                                                                   parameters.xyNeighborFactor,
                                                                   parameters.excludeCornerNeighbors,
                                                                   parameters.excludeSameLayerNeighbors,
                                                                   parameters.excludeSameSectionNeighbors);
            if (existingPairs.size() > 0) {
                final int beforeSize = currentNeighborPairs.size();
                currentNeighborPairs.removeAll(existingPairs);
                final int afterSize = currentNeighborPairs.size();
                LOG.info("removed {} existing pairs for z {}", (beforeSize - afterSize), z);
            }

            neighborPairs.addAll(currentNeighborPairs);
        }

        LOG.info("getSortedNeighborPairs: exit, returning {} pairs", neighborPairs.size());

        return new ArrayList<>(neighborPairs);
    }

    @Nonnull
    private Map<Double, TileBoundsRTree> buildRTrees(final List<Double> zValues)
            throws IOException {

        final Map<Double, TileBoundsRTree> zToTreeMap = new LinkedHashMap<>();

        long totalTileCount = 0;
        long filteredTileCount = 0;

        for (final Double z : zValues) {

            List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
            TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

            totalTileCount += tileBoundsList.size();

            if (filterTilesWithBox) {

                final int unfilteredCount = tileBoundsList.size();

                tileBoundsList = tree.findTilesInBox(parameters.minX, parameters.minY,
                                                     parameters.maxX, parameters.maxY);

                if (unfilteredCount > tileBoundsList.size()) {

                    LOG.info("buildRTrees: removed {} tiles outside of bounding box",
                             (unfilteredCount - tileBoundsList.size()));

                    tree = new TileBoundsRTree(z, tileBoundsList);
                }
            }

            if (parameters.excludeCompletelyObscuredTiles) {

                final int unfilteredCount = tileBoundsList.size();

                tileBoundsList = tree.findVisibleTiles();

                if (unfilteredCount > tileBoundsList.size()) {

                    LOG.info("buildRTrees: removed {} completely obscured tiles",
                             (unfilteredCount - tileBoundsList.size()));

                    tree = new TileBoundsRTree(z, tileBoundsList);
                }
            }

            zToTreeMap.put(z, tree);

            filteredTileCount += tileBoundsList.size();
        }

        LOG.info("buildRTrees: added bounds for {} out of {} tiles to {} trees",
                 filteredTileCount, totalTileCount, zToTreeMap.size());

        return zToTreeMap;
    }

    private Set<OrderedCanvasIdPair> getExistingPairs()
            throws IOException {

        final Set<OrderedCanvasIdPair> existingPairs = new HashSet<>(8192);

        if (parameters.excludePairsInMatchCollection != null) {

            final List<SectionData> stackSectionDataList =
                    renderDataClient.getStackSectionData(parameters.stack,
                                                         parameters.minZ,
                                                         parameters.maxZ);

            final RenderDataClient matchDataClient =
                    new RenderDataClient(parameters.baseDataUrl,
                                         parameters.owner,
                                         parameters.excludePairsInMatchCollection);

            String pGroupId;
            for (final SectionData sectionData: stackSectionDataList) {
                pGroupId = sectionData.getSectionId();
                for (final CanvasMatches canvasMatches : matchDataClient.getMatchesWithPGroupId(pGroupId)) {
                    existingPairs.add(
                            new OrderedCanvasIdPair(
                                    new CanvasId(canvasMatches.getpGroupId(), canvasMatches.getpId()),
                                    new CanvasId(canvasMatches.getqGroupId(), canvasMatches.getqId())));
                }
            }

        }

        return existingPairs;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TilePairClient.class);
}
