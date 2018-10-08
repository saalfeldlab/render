package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for calculating neighbor pairs for all tiles in a range of sections.
 *
 * @author Eric Trautman
 */
public class TilePairClient {

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

        @Parameter(names = "--minZ", description = "Minimum Z value for all tiles", required = true)
        public Double minZ;

        @Parameter(names = "--maxZ", description = "Maximum Z value for all tiles", required = true)
        public Double maxZ;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles"
        )
        public Double xyNeighborFactor = 0.9;

        @Parameter(
                names = "--explicitRadius",
                description = "Explit radius in full scale pixels for locating neighbor tiles (if set, will override --xyNeighborFactor)"
        )
        public Double explicitRadius;

        @Parameter(
                names = "--useRowColPositions",
                description = "For montage pairs (zNeighborDistance == 0) use layout imageRow and imageCol values instead of tile bounds to identify neighbor tiles",
                arity = 0)
        public boolean useRowColPositions = false;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value"
        )
        public Integer zNeighborDistance = 2;

        @Parameter(
                names = "--excludeCornerNeighbors",
                description = "Exclude neighbor tiles whose center x and y is outside the source tile's x and y range respectively",
                arity = 1)
        public boolean excludeCornerNeighbors = true;

        @Parameter(
                names = "--excludeCompletelyObscuredTiles",
                description = "Exclude tiles that are completely obscured by reacquired tiles",
                arity = 1)
        public boolean excludeCompletelyObscuredTiles = true;

        @Parameter(
                names = "--excludeSameLayerNeighbors",
                description = "Exclude neighbor tiles in the same layer (z) as the source tile",
                arity = 1)
        public boolean excludeSameLayerNeighbors = false;

        @Parameter(
                names = "--excludeSameSectionNeighbors",
                description = "Exclude neighbor tiles with the same sectionId as the source tile",
                arity = 1)
        public boolean excludeSameSectionNeighbors = false;

        @Parameter(
                names = "--excludePairsInMatchCollection",
                description = "Name of match collection whose existing pairs should be excluded from the generated list (default is to include all pairs)"
        )
        public String excludePairsInMatchCollection;

        @Parameter(
                names = "--existingMatchOwner",
                description = "Owner of match collection whose existing pairs should be excluded from the generated list (default is owner)"
        )
        public String existingMatchOwner;

        @Parameter(names = "--minExistingMatchCount", description = "Minimum number of existing matches to trigger pair exclusion")
        public Integer minExistingMatchCount = 0;

        @Parameter(
                names = "--onlyIncludeTilesFromStack",
                description = "Name of match collection whose existing pairs should be excluded from the generated list (default is to include all pairs)"
        )
        public String onlyIncludeTilesFromStack;

        @Parameter(names = "--toJson", description = "JSON file where tile pairs are to be stored (.json, .gz, or .zip)", required = true)
        public String toJson;

        @Parameter(names = "--maxPairsPerFile", description = "Maximum number of pairs to include in each file.")
        public Integer maxPairsPerFile = 100000;

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

        String getExistingMatchOwner() {
            if (existingMatchOwner == null) {
                existingMatchOwner = renderWeb.owner;
            }
            return existingMatchOwner;
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

                final TilePairClient client = new TilePairClient(parameters);

                client.deriveAndSaveSortedNeighborPairs();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final boolean filterTilesWithBox;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient includeClient;
    private final StackId includeStack;
    private String outputFileNamePrefix;
    private String outputFileNameSuffix;
    private int numberOfOutputFiles;

    TilePairClient(final Parameters parameters) throws IllegalArgumentException {

        this.parameters = parameters;
        this.filterTilesWithBox = (parameters.bounds.minX != null);

        this.renderDataClient = parameters.renderWeb.getDataClient();

        if (parameters.onlyIncludeTilesFromStack == null) {
            includeClient = null;
            includeStack = null;
        } else {
            includeStack = StackId.fromNameString(parameters.onlyIncludeTilesFromStack,
                                                  parameters.renderWeb.owner,
                                                  parameters.renderWeb.project);
            includeClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                 includeStack.getOwner(),
                                                 includeStack.getProject());
        }

        this.outputFileNamePrefix = parameters.toJson;
        this.outputFileNameSuffix = "";
        final Pattern p = Pattern.compile("^(.*)\\.(json|gz|zip)$");
        final Matcher m = p.matcher(parameters.toJson);
        if (m.matches() && (m.groupCount() == 2)) {
            this.outputFileNamePrefix = m.group(1);
            this.outputFileNameSuffix = "." + m.group(2);
            if (this.outputFileNamePrefix.endsWith(".json")) {
                this.outputFileNamePrefix =
                        this.outputFileNamePrefix.substring(0, this.outputFileNamePrefix.length() - 5);
                this.outputFileNameSuffix = ".json" + this.outputFileNameSuffix;
            }
        }

        this.numberOfOutputFiles = 0;
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

    public List<Double> getZValues()
            throws IOException {
        return renderDataClient.getStackZValues(parameters.stack,
                                                parameters.minZ,
                                                parameters.maxZ);
    }

    void deriveAndSaveSortedNeighborPairs()
            throws IOException {

        LOG.info("deriveAndSaveSortedNeighborPairs: entry");

        final String renderParametersUrlTemplate = getRenderParametersUrlTemplate();

        final List<Double> zValues = getZValues();

        final ExistingMatchHelper existingMatchHelper;
        if (parameters.excludePairsInMatchCollection == null) {
            existingMatchHelper = null;
        } else {
            existingMatchHelper = new ExistingMatchHelper(parameters, renderDataClient);
        }

        final Map<Double, TileBoundsRTree> zToTreeMap = new LinkedHashMap<>(zValues.size());

        // load the first zNeighborDistance trees
        double z;
        for (int zIndex = 0; (zIndex < zValues.size()) && (zIndex < parameters.zNeighborDistance); zIndex++) {
            z = zValues.get(zIndex);
            zToTreeMap.put(z, buildRTree(z));
            if (existingMatchHelper != null) {
                existingMatchHelper.addExistingPairs(z);
            }
        }

        // edge case: add existing montage pairs (distance == 0)
        if ((parameters.zNeighborDistance == 0) && (existingMatchHelper != null)) {
            existingMatchHelper.addExistingPairs(zValues.get(0));
        }

        final Set<OrderedCanvasIdPair> neighborPairs = new TreeSet<>();

        int totalSavedPairCount = 0;
        Double neighborZ;
        TileBoundsRTree currentZTree;
        List<TileBoundsRTree> neighborTreeList;
        Set<OrderedCanvasIdPair> currentNeighborPairs;
        for (int zIndex = 0; zIndex < zValues.size(); zIndex++) {

            z = zValues.get(zIndex);

            if (parameters.zNeighborDistance == 0) {
                zToTreeMap.put(z, buildRTree(z));
            }

            neighborTreeList = new ArrayList<>();

            final double idealMaxNeighborZ = Math.min(parameters.maxZ, z + parameters.zNeighborDistance);
            for (int neighborZIndex = zIndex + 1; neighborZIndex < zValues.size(); neighborZIndex++) {

                neighborZ = zValues.get(neighborZIndex);

                if (neighborZ > idealMaxNeighborZ) {
                    break;
                }

                if (! zToTreeMap.containsKey(neighborZ)) {
                    if (zIndex > 0) {
                        final double completedZ = zValues.get(zIndex - 1);
                        zToTreeMap.remove(completedZ);
                    }
                    zToTreeMap.put(neighborZ, buildRTree(neighborZ));
                    if (existingMatchHelper != null) {
                        existingMatchHelper.addExistingPairs(neighborZ);
                    }
                }

                neighborTreeList.add(zToTreeMap.get(neighborZ));
            }

            currentZTree = zToTreeMap.get(z);

            currentNeighborPairs = currentZTree.getCircleNeighbors(neighborTreeList,
                                                                   parameters.xyNeighborFactor,
                                                                   parameters.explicitRadius,
                                                                   parameters.excludeCornerNeighbors,
                                                                   parameters.excludeSameLayerNeighbors,
                                                                   parameters.excludeSameSectionNeighbors);
            if (existingMatchHelper != null) {
                existingMatchHelper.removeExistingPairs(z, currentNeighborPairs);

                // edge case: add existing montage pairs (distance == 0) for next z
                final int nextIndex = zIndex + 1;
                if ((parameters.zNeighborDistance == 0) && (nextIndex < zValues.size())) {
                    existingMatchHelper.addExistingPairs(zValues.get(nextIndex));
                }

            }

            neighborPairs.addAll(currentNeighborPairs);

            if (neighborPairs.size() > parameters.maxPairsPerFile) {
                final List<OrderedCanvasIdPair> neighborPairsList = new ArrayList<>(neighborPairs);
                int fromIndex = 0;
                for (; ; fromIndex += parameters.maxPairsPerFile) {
                    final int toIndex = fromIndex + parameters.maxPairsPerFile;
                    if (toIndex <= neighborPairs.size()) {
                        savePairs(neighborPairsList.subList(fromIndex, toIndex),
                                  renderParametersUrlTemplate,
                                  getOutputFileName());
                        numberOfOutputFiles++;
                        totalSavedPairCount += parameters.maxPairsPerFile;
                    } else {
                        break;
                    }
                }

                neighborPairs.clear();
                neighborPairs.addAll(neighborPairsList.subList(fromIndex, neighborPairsList.size()));

            }

        }

        if (neighborPairs.size() > 0) {
            final List<OrderedCanvasIdPair> neighborPairsList = new ArrayList<>(neighborPairs);
            final String outputFileName = numberOfOutputFiles == 0 ? parameters.toJson : getOutputFileName();
            savePairs(neighborPairsList, renderParametersUrlTemplate, outputFileName);
            totalSavedPairCount += neighborPairs.size();
        }

        LOG.info("deriveAndSaveSortedNeighborPairs: exit, saved {} total pairs", totalSavedPairCount);
    }

    @Nonnull
    public TileBoundsRTree buildRTree(final double z)
            throws IOException {

        TileBoundsRTree tree;
        List<TileBounds> tileBoundsList;
        final int totalTileCount;

        if ((parameters.zNeighborDistance == 0) && (parameters.useRowColPositions)) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final Collection<TileSpec> tileSpecs = resolvedTiles.getTileSpecs();

            tileBoundsList = new ArrayList<>(tileSpecs.size());
            totalTileCount = tileBoundsList.size();

            final int tileSize = 10;
            for (final TileSpec tileSpec : tileSpecs) {
                final LayoutData layoutData = tileSpec.getLayout();
                if (layoutData == null) {
                    throw new IOException("tile '" + tileSpec.getTileId() + "' is missing layout data");
                }
                final Integer imageRow = layoutData.getImageRow();
                final Integer imageCol = layoutData.getImageCol();
                if ((imageRow == null) || (imageCol == null)) {
                    throw new IOException("tile '" + tileSpec.getTileId() +
                                          "' is missing layout imageRow and/or imageCol data");
                }
                final double minX = imageCol * tileSize;
                final double minY = imageRow * tileSize;
                tileBoundsList.add(new TileBounds(tileSpec.getTileId(), layoutData.getSectionId(), tileSpec.getZ(),
                                                  minX, minY, minX + tileSize, minY + tileSize));
            }

            tree = new TileBoundsRTree(z, tileBoundsList);

        } else {

            tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
            totalTileCount = tileBoundsList.size();

            if (includeClient != null) {

                final List<TileBounds> includeList = includeClient.getTileBounds(includeStack.getStack(), z);
                final Set<String> includeTileIds = new HashSet<>(includeList.size() * 2);
                for (final TileBounds bounds : includeList) {
                    includeTileIds.add(bounds.getTileId());
                }

                tileBoundsList.removeIf(tileBounds -> !includeTileIds.contains(tileBounds.getTileId()));

                if (totalTileCount > tileBoundsList.size()) {
                    LOG.info("buildRTree: removed {} tiles not found in {}",
                             (totalTileCount - tileBoundsList.size()), includeStack);
                }

            }

            tree = new TileBoundsRTree(z, tileBoundsList);

            if (filterTilesWithBox) {

                final int unfilteredCount = tileBoundsList.size();

                tileBoundsList = tree.findTilesInBox(parameters.bounds.minX, parameters.bounds.minY,
                                                     parameters.bounds.maxX, parameters.bounds.maxY);

                if (unfilteredCount > tileBoundsList.size()) {

                    LOG.info("buildRTree: removed {} tiles outside of bounding box",
                             (unfilteredCount - tileBoundsList.size()));

                    tree = new TileBoundsRTree(z, tileBoundsList);
                }
            }

            if (parameters.excludeCompletelyObscuredTiles) {

                final int unfilteredCount = tileBoundsList.size();

                tileBoundsList = tree.findVisibleTiles();

                if (unfilteredCount > tileBoundsList.size()) {

                    LOG.info("buildRTree: removed {} completely obscured tiles",
                             (unfilteredCount - tileBoundsList.size()));

                    tree = new TileBoundsRTree(z, tileBoundsList);
                }
            }
        }

        LOG.info("buildRTree: added bounds for {} out of {} tiles for z {}",
                 tileBoundsList.size(), totalTileCount, z);

        return tree;
    }

    private String getOutputFileName() {
        return String.format("%s_p%03d%s", outputFileNamePrefix, numberOfOutputFiles, outputFileNameSuffix);
    }

    private void savePairs(final List<OrderedCanvasIdPair> neighborPairs,
                           final String renderParametersUrlTemplate,
                           final String outputFileName)
            throws IOException {

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                new RenderableCanvasIdPairs(renderParametersUrlTemplate,
                                            neighborPairs);
        FileUtil.saveJsonFile(outputFileName, renderableCanvasIdPairs);
    }

    private class ExistingMatchHelper {

        final List<SectionData> stackSectionDataList;
        final Map<Double, List<String>> zToSectionIdMap;
        final RenderDataClient matchDataClient;
        final Set<OrderedCanvasIdPair> existingPairs;

        ExistingMatchHelper(final Parameters clientParameters,
                            final RenderDataClient renderDataClient)
                throws IOException {

            stackSectionDataList = renderDataClient.getStackSectionData(clientParameters.stack,
                                                                        clientParameters.minZ,
                                                                        clientParameters.maxZ);

            zToSectionIdMap = new HashMap<>(stackSectionDataList.size());
            for (final SectionData sectionData : stackSectionDataList) {
                final List<String> sectionIdList = zToSectionIdMap.computeIfAbsent(sectionData.getZ(),
                                                                                   z -> new ArrayList<>());
                sectionIdList.add(sectionData.getSectionId());
            }

            matchDataClient = new RenderDataClient(clientParameters.renderWeb.baseDataUrl,
                                                   clientParameters.getExistingMatchOwner(),
                                                   clientParameters.excludePairsInMatchCollection);

            existingPairs = new LinkedHashSet<>(8192); // order is important for later removal of matches
        }

        void addExistingPairs(final double z)
                throws IOException {

            final List<String> groupIds = zToSectionIdMap.get(z);
            if (groupIds != null) {
                for (final String pGroupId : groupIds) {
                    for (final CanvasMatches canvasMatches : matchDataClient.getMatchesWithPGroupId(pGroupId)) {
                        if (canvasMatches.size() > parameters.minExistingMatchCount) {
                            existingPairs.add(
                                    new OrderedCanvasIdPair(
                                            new CanvasId(canvasMatches.getpGroupId(), canvasMatches.getpId()),
                                            new CanvasId(canvasMatches.getqGroupId(), canvasMatches.getqId())));
                        }
                    }
                }
            }
        }

        void removeExistingPairs(final double currentZ,
                                 final Set<OrderedCanvasIdPair> currentNeighborPairs) {

            int beforeSize = currentNeighborPairs.size();

            // currentNeighborPairs have montage relative position but existing pairs do not,
            // so we need to remove the position before searching for matches

            for (final Iterator<OrderedCanvasIdPair> i = currentNeighborPairs.iterator(); i.hasNext();) {

                final OrderedCanvasIdPair pair = i.next();
                final CanvasId pId = pair.getP();
                final CanvasId qId = pair.getQ();
                final CanvasId pIdWithoutPosition = new CanvasId(pId.getGroupId(), pId.getId());
                final CanvasId qIdWithoutPosition = new CanvasId(qId.getGroupId(), qId.getId());
                
                if (existingPairs.contains(new OrderedCanvasIdPair(pIdWithoutPosition, qIdWithoutPosition))) {
                    i.remove();
                }
            }

            LOG.info("removeExistingPairs: removed {} existing pairs for z {}",
                     (beforeSize - currentNeighborPairs.size()), currentZ);

            final List<String> groupIds = zToSectionIdMap.get(currentZ);
            if (groupIds != null) {

                beforeSize = existingPairs.size();

                final Set<String> groupIdsToRemove = new HashSet<>(groupIds);

                for (final Iterator<OrderedCanvasIdPair> i = existingPairs.iterator(); i.hasNext();) {
                    final OrderedCanvasIdPair pair = i.next();
                    if (groupIdsToRemove.contains(pair.getP().getGroupId())) {
                        i.remove();
                    } else {
                        break;
                    }
                }

                LOG.info("removeExistingPairs: stopped tracking {} pairs with pGroupIds {}",
                         beforeSize - existingPairs.size(), groupIdsToRemove);
            }

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TilePairClient.class);

}
