package org.janelia.render.client;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.lang.math.DoubleRange;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;

/**
 * Java client for ...
 *
 * @author Eric Trautman
 */
@SuppressWarnings("rawtypes")
public class RestartSolverClient<B extends Model< B > & Affine2D< B >> {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--matchOwner",
                description = "Owner of match collection for tiles (default is owner)"
        )
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Name of match collection for tiles",
                required = true
        )
        public String matchCollection;

        @Parameter(
                names = "--regularizerModelType",
                description = "Type of model for regularizer",
                required = true
        )
        public ModelType regularizerModelType;

        @Parameter(
                names = "--samplesPerDimension",
                description = "Samples per dimension"
        )
        public Integer samplesPerDimension = 2;

        @Parameter(
                names = "--maxAllowedError",
                description = "Max allowed error"
        )
        public Double maxAllowedError = 200.0;

        @Parameter(
                names = "--maxIterations",
                description = "Max iterations"
        )
        public Integer maxIterations = 2000;

        @Parameter(
                names = "--maxPlateauWidth",
                description = "Max allowed error"
        )
        public Integer maxPlateauWidth = 200;

        // TODO: determine good default for optimizer lambdas
        @Parameter(
                names = "--optimizerLambdas",
                description = "Explicit optimizer lambda values.",
                variableArity = true
        )
        public List<Double> optimizerLambdas = Arrays.asList(1.0, 0.5, 0.1, 0.01);

        @Parameter(
                names = "--targetOwner",
                description = "Owner name for aligned result stack (default is same as owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Project name for aligned result stack (default is same as project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name for aligned result stack (if omitted, aligned models are simply logged)")
        public String targetStack;

        @Parameter(names = "--threads", description = "Number of threads to be used")
        public int numberOfThreads = 1;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles"
        )
        public Double xyNeighborFactor = 0.6;

        public Parameters() {
        }

        @SuppressWarnings("Duplicates")
        void initDefaultValues() {

            if (this.matchOwner == null) {
                this.matchOwner = renderWeb.owner;
            }

            if (this.targetOwner == null) {
                this.targetOwner = renderWeb.owner;
            }

            if (this.targetProject == null) {
                this.targetProject = renderWeb.project;
            }
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_33m_BR",
                            "--project", "Sec10",

                            "--stack", "v3_acquire",

                            "--targetStack", "v3_acquire_pre_align",
                            "--regularizerModelType", "RIGID",
                            "--optimizerLambdas", "1.0,0.5,0.1,0.01",
                            "--maxIterations", "1000",

                            "--threads", "1",
                            "--matchCollection", "Sec10_multi"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final RestartSolverClient client = new RestartSolverClient(parameters);

                client.run();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final RenderDataClient targetDataClient;

    private final StackMetaData sourceStackMetaData;

    private RestartSolverClient(final Parameters parameters)
            throws IOException {

        parameters.initDefaultValues();

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.matchOwner,
                                                    parameters.matchCollection);

        this.sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);

        if (parameters.targetStack == null) {
            this.targetDataClient = null;
        } else {
            this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                         parameters.targetOwner,
                                                         parameters.targetProject);
        }

    }

    private void run()
            throws IOException, ExecutionException, InterruptedException {

        LOG.info("run: entry");

        final String restartGroupId = "restart";
        final ResolvedTileSpecCollection restartTileSpecCollection =
                renderDataClient.getResolvedTiles(parameters.stack, null, null,
                                                  restartGroupId,
                                                  null, null, null, null);

        final Map<Double, TileBoundsRTree> zToTileBounds = new HashMap<>();
        final Set<Double> patchedLayerZValues = new HashSet<>();
        for (final TileSpec tileSpec : restartTileSpecCollection.getTileSpecs()) {
            final Double z = tileSpec.getZ();
            final TileBoundsRTree tree =
                    zToTileBounds.computeIfAbsent(z, t -> new TileBoundsRTree(z, new ArrayList<>()));
            tree.addTile(tileSpec.toTileBounds());
            if (tileSpec.hasLabel("patch")) {
                patchedLayerZValues.add(tileSpec.getZ());
            }
            if (tileSpec.getLastTransform() instanceof ReferenceTransformSpec) {
                throw new IllegalStateException(
                        "last transform for tile " + tileSpec.getTileId() +
                        " is a reference transform which will break this fragile client, " +
                        "make sure --stack is not a rough aligned stack ");
            }
        }

        removePrePatchLayers(restartTileSpecCollection, zToTileBounds, patchedLayerZValues);

        final List<Double> orderedZValues = zToTileBounds.keySet().stream().sorted().collect(Collectors.toList());

        if (orderedZValues.size() % 2 != 0) {
            throw new IllegalStateException(
                    "after collapsing patched layers, " + orderedZValues.size() +
                    " restart layers (an uneven number) were found, z values are: " + orderedZValues);
        }

        final List<DoubleRange> consistentLayerRanges = getConsistentLayerRanges(orderedZValues);
        final Map<OrderedCanvasIdPair, CanvasMatches> pairToMatchesMap = getMatches(zToTileBounds,
                                                                                    consistentLayerRanges);

        for (int nextRangeIndex = 1; nextRangeIndex < consistentLayerRanges.size(); nextRangeIndex++) {
            solveRestart(nextRangeIndex,
                         restartTileSpecCollection,
                         zToTileBounds,
                         consistentLayerRanges,
                         pairToMatchesMap);
        }

        if (restartTileSpecCollection.getTileCount() > 0) {
            savePreAlignStacks(restartTileSpecCollection, zToTileBounds, consistentLayerRanges);
        } else {
            LOG.info("run: stack {} does not contain any restart group tiles", parameters.stack);
        }

        LOG.info("run: exit, finished pre-alignment for consistent layer ranges {}", consistentLayerRanges);
    }

    private String getRowAndColumnKey(final TileSpec tileSpec) {
        final LayoutData layout = tileSpec.getLayout();
        return layout.getImageRow() + ":" + layout.getImageCol();
    }

    /**
     * @return list of z ranges with consistently acquired tiles.
     */
    private List<DoubleRange> getConsistentLayerRanges(final List<Double> orderedZValues) {
        final List<DoubleRange> consistentLayerRanges = new ArrayList<>();
        final Bounds stackBounds = sourceStackMetaData.getStats().getStackBounds();
        Double minZ = stackBounds.getMinZ();
        Double maxZ;
        for (int i = 1; i < orderedZValues.size(); i+=2) {
            maxZ = orderedZValues.get(i - 1);
            consistentLayerRanges.add(new DoubleRange(minZ, maxZ));
            minZ = orderedZValues.get(i);
        }
        consistentLayerRanges.add(new DoubleRange(minZ, stackBounds.getMaxZ()));
        LOG.info("getConsistentLayerRanges: returning {}", consistentLayerRanges);
        return consistentLayerRanges;
    }

    /**
     * @return "standard" set of montage/same layer tile pairs for all tiles in layers before and after restarts.
     */
    private Set<OrderedCanvasIdPair> getSameLayerPairs(final Map<Double, TileBoundsRTree> zToTileBounds) {
        final Set<OrderedCanvasIdPair> sameLayerPairs = new HashSet<>();
        final List<TileBoundsRTree> emptyNeighborTreeList = new ArrayList<>();
        zToTileBounds.values().forEach(boundsTree -> {
            final Set<OrderedCanvasIdPair> pairs =
                    boundsTree.getCircleNeighbors(boundsTree.getTileBoundsList(),
                                                  emptyNeighborTreeList,
                                                  parameters.xyNeighborFactor,
                                                  null,
                                                  true,
                                                  false,
                                                  false);
            sameLayerPairs.addAll(pairs);
        });
        LOG.info("getSameLayerPairs: returning {} pairs", sameLayerPairs.size());
        return sameLayerPairs;
    }

    private Set<OrderedCanvasIdPair> getSameLayerPairsWithoutPositions(final Set<OrderedCanvasIdPair> sameLayerPairs) {
        final Set<OrderedCanvasIdPair> sameLayerPairsWithoutPositions = new HashSet<>();
        for (final OrderedCanvasIdPair pair : sameLayerPairs) {
            final CanvasId p = pair.getP();
            final CanvasId q = pair.getQ();
            sameLayerPairsWithoutPositions.add(new OrderedCanvasIdPair(new CanvasId(p.getGroupId(), p.getId()),
                                                                       new CanvasId(q.getGroupId(), q.getId()),
                                                                       null));


        }
        return sameLayerPairsWithoutPositions;
    }

    /**
     * @return cross layer tile pairs for cartesian product of tiles in layers before and after restarts.
     */
    private Set<OrderedCanvasIdPair> getCrossLayerPairs(final Map<Double, TileBoundsRTree> zToTileBounds,
                                                        final List<DoubleRange> consistentLayerRanges) {
        final Set<OrderedCanvasIdPair> crossLayerPairs = new HashSet<>();
        double fromZ = consistentLayerRanges.get(0).getMaximumDouble();
        for (int i = 1; i < consistentLayerRanges.size(); i++) {
            final DoubleRange consistentLayerRange = consistentLayerRanges.get(i);
            final TileBoundsRTree fromBoundsRTree = zToTileBounds.get(fromZ);
            final TileBoundsRTree toBoundsRTree = zToTileBounds.get(consistentLayerRange.getMinimumDouble());
            final List<TileBounds> toBoundsList = toBoundsRTree.getTileBoundsList();
            for (final TileBounds fromTileBounds : fromBoundsRTree.getTileBoundsList()) {
                crossLayerPairs.addAll(
                        TileBoundsRTree.getDistinctPairs(fromTileBounds,
                                                         toBoundsList,
                                                         false,
                                                         true,
                                                         false));
            }
            fromZ = consistentLayerRange.getMaximumDouble();
        }
        LOG.info("getCrossLayerPairs: returning {} pairs", crossLayerPairs.size());
        return crossLayerPairs;
    }

    /**
     * Throws out any pre-patch layers since tiles should be the same.
     */
    private void removePrePatchLayers(final ResolvedTileSpecCollection restartTileSpecCollection,
                                      final Map<Double, TileBoundsRTree> zToTileBounds,
                                      final Set<Double> patchedLayerZValues) {
        //
        patchedLayerZValues.stream().sorted().forEach(z -> {
            final Double prePatchZ = z - 1;
            final TileBoundsRTree prePatchBounds = zToTileBounds.remove(prePatchZ);
            if (prePatchBounds != null) {
                LOG.info("removePrePatchLayers: removed {} tiles with z {}", prePatchBounds.size(), prePatchZ);
                restartTileSpecCollection.removeTileSpecs(prePatchBounds.getTileIds());
            }
        });
    }

    private Map<OrderedCanvasIdPair, CanvasMatches> getMatches(final Map<Double, TileBoundsRTree> zToTileBounds,
                                                               final List<DoubleRange> consistentLayerRanges)
            throws IOException {
        final Map<OrderedCanvasIdPair, CanvasMatches> pairToMatchesMap = new HashMap<>();

        final Set<OrderedCanvasIdPair> sameLayerPairs = getSameLayerPairs(zToTileBounds);
        final Set<OrderedCanvasIdPair> sameLayerPairsWithoutPositions =
                getSameLayerPairsWithoutPositions(sameLayerPairs);
        final Set<OrderedCanvasIdPair> crossLayerPairs = getCrossLayerPairs(zToTileBounds,
                                                                            consistentLayerRanges);
        final Set<String> pGroupIds = sameLayerPairs.stream()
                .map(pair -> pair.getP().getGroupId())
                .collect(Collectors.toSet());

        for (final String pGroupId : pGroupIds) {

            for (final CanvasMatches canvasMatches : matchDataClient.getMatchesWithPGroupId(pGroupId,
                                                                                            false)) {
                final OrderedCanvasIdPair pair =
                        new OrderedCanvasIdPair(new CanvasId(canvasMatches.getpGroupId(), canvasMatches.getpId()),
                                                new CanvasId(canvasMatches.getqGroupId(), canvasMatches.getqId()),
                                                null);
                if (sameLayerPairsWithoutPositions.contains(pair) || crossLayerPairs.contains(pair)) {
                    pairToMatchesMap.put(pair, canvasMatches);
                }
            }

        }

        final int totalNumberOfRestartPairs = sameLayerPairs.size() + crossLayerPairs.size();
        LOG.info("getMatches: matches for {} out of {} restart pairs already exist",
                 pairToMatchesMap.size(), totalNumberOfRestartPairs);

        sameLayerPairsWithoutPositions.stream().sorted().forEach(pair -> {
            if (! pairToMatchesMap.containsKey(pair)) {
                // TODO: generate and store same layer matches (use relative position info)
                LOG.warn("getMatches: need to generate same layer matches for {}", pair);
            }
        });

        crossLayerPairs.stream().sorted().forEach(pair -> {
            if (! pairToMatchesMap.containsKey(pair)) {
                // TODO: generate and store cross layer matches (throw any out?)
                LOG.warn("getMatches: need to generate cross matches for {}", pair);
            }
        });

        // TODO: handle any remaining missing connections (by faking connections at overlap?) only matters for islands

        return pairToMatchesMap;
    }

    private void solveRestart(final int nextRangeIndex,
                              final ResolvedTileSpecCollection restartTileSpecCollection,
                              final Map<Double, TileBoundsRTree> zToTileBounds,
                              final List<DoubleRange> consistentLayerRanges,
                              final Map<OrderedCanvasIdPair, CanvasMatches> pairToMatchesMap)
            throws ExecutionException, InterruptedException {

        final DoubleRange currentRange = consistentLayerRanges.get(nextRangeIndex - 1);
        final DoubleRange nextRange = consistentLayerRanges.get(nextRangeIndex);
        final Double currentMaxZ = currentRange.getMaximumDouble();
        final Double nextMinZ = nextRange.getMinimumDouble();
        final Double nextMaxZ = nextRange.getMaximumDouble();

        final Set<String> currentTileIds = zToTileBounds.get(currentMaxZ).getTileIds();
        final Set<String> nextMinTileIds = zToTileBounds.get(nextMinZ).getTileIds();

        final Set<String> allTileIds = new HashSet<>(currentTileIds);
        allTileIds.addAll(nextMinTileIds);

        final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();

        buildTilesForSolve(restartTileSpecCollection, pairToMatchesMap, allTileIds, idToTileMap);

        final TileConfiguration tileConfig = new TileConfiguration();
        tileConfig.addTiles(idToTileMap.values());

        // fix the first tile in the current layer
        final String firstCurrentMaxTileId = currentTileIds.stream().sorted().findFirst().orElse(null);
        tileConfig.fixTile(idToTileMap.get(firstCurrentMaxTileId));

        LOG.info("solveRestart: optimizing {} tiles between z {} and {}",
                 idToTileMap.size(), currentMaxZ, nextMinZ);

        final List<Double> lambdaValues = parameters.optimizerLambdas.stream()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        //noinspection DuplicatedCode
        for (final double lambda : lambdaValues) {

            for (final Tile tile : idToTileMap.values()) {
                ((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
            }

            final ErrorStatistic observer = new ErrorStatistic(parameters.maxPlateauWidth + 1);
            final float damp = 1.0f;
            TileUtil.optimizeConcurrently(observer,
                                          parameters.maxAllowedError,
                                          parameters.maxIterations,
                                          parameters.maxPlateauWidth,
                                          damp,
                                          tileConfig,
                                          tileConfig.getTiles(),
                                          tileConfig.getFixedTiles(),
                                          parameters.numberOfThreads);
        }

        final Map<String, String> nextMinToMaxTileIdMap = new HashMap<>();

        final TileBoundsRTree nextMaxTree = zToTileBounds.get(nextMaxZ);
        if (nextMaxTree != null) {
            final Set<String> nextMaxTileIds = zToTileBounds.get(nextMaxZ).getTileIds();
            for (final String nextMinTileId : zToTileBounds.get(nextMinZ).getTileIds()) {
                final String samePositionMaxTileId = findSamePositionTileId(nextMinTileId,
                                                                            nextMaxTileIds,
                                                                            restartTileSpecCollection);
                nextMinToMaxTileIdMap.put(nextMinTileId, samePositionMaxTileId);
            }
        }

        for (final String tileId : allTileIds) {
            final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
            final TransformSpec transformSpec = Trakem2SolverClient.getTransformSpec(tile.getModel());
            restartTileSpecCollection.addTransformSpecToTile(tileId, transformSpec, REPLACE_LAST);

            final String samePositionMaxTileId = nextMinToMaxTileIdMap.get(tileId);
            if (samePositionMaxTileId != null) {
                restartTileSpecCollection.addTransformSpecToTile(samePositionMaxTileId, transformSpec, REPLACE_LAST);
            }
        }

    }

    private void buildTilesForSolve(final ResolvedTileSpecCollection restartTileSpecCollection,
                                    final Map<OrderedCanvasIdPair, CanvasMatches> pairToMatchesMap,
                                    final Set<String> allTileIds,
                                    final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap) {

        for (final OrderedCanvasIdPair pair : pairToMatchesMap.keySet()) {

            final String pId = pair.getP().getId();
            final String qId = pair.getQ().getId();

            if (allTileIds.contains(pId) && allTileIds.contains(qId)) {
                final TileSpec pTileSpec = restartTileSpecCollection.getTileSpec(pId);
                final TileSpec qTileSpec = restartTileSpecCollection.getTileSpec(qId);

                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p =
                        idToTileMap.computeIfAbsent(
                                pId,
                                pTile -> Trakem2SolverClient.buildTileFromSpec(pTileSpec,
                                                                               parameters.samplesPerDimension,
                                                                               parameters.regularizerModelType));

                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> q =
                        idToTileMap.computeIfAbsent(
                                qId,
                                qTile -> Trakem2SolverClient.buildTileFromSpec(qTileSpec,
                                                                               parameters.samplesPerDimension,
                                                                               parameters.regularizerModelType));

                final CanvasMatches canvasMatches = pairToMatchesMap.get(pair);
                p.connect(q,
                          CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches()));
            }
        }
    }

    private String findSamePositionTileId(final String forTileId,
                                          final Set<String> withTileIds,
                                          final ResolvedTileSpecCollection tileSpecCollection) {
        final LayoutData forLayout = tileSpecCollection.getTileSpec(forTileId).getLayout();
        return withTileIds.stream().filter(withTileId -> {
            final LayoutData withLayout = tileSpecCollection.getTileSpec(withTileId).getLayout();
            return forLayout.getImageCol().equals(withLayout.getImageCol()) &&
                   forLayout.getImageRow().equals(withLayout.getImageRow());
        }).findFirst().orElse(null);
    }

    private void savePreAlignStacks(final ResolvedTileSpecCollection restartTileSpecCollection,
                                    final Map<Double, TileBoundsRTree> zToTileBounds,
                                    final List<DoubleRange> consistentLayerRanges)
            throws IOException {

        // save pre-aligned restart tiles
        final String preAlignRestartStackName = parameters.targetStack + "_restart";
        saveTargetTileSpecs(preAlignRestartStackName, restartTileSpecCollection, true);

        // save target tiles
        for (final DoubleRange range : consistentLayerRanges) {

            final Double rangeMinZ = range.getMinimumDouble();
            final Double rangeMaxZ = range.getMaximumDouble();

            final Map<String, TransformSpec> rowColumnToTransform = new HashMap<>();

            final Double sourceTransformZ = zToTileBounds.containsKey(rangeMinZ) ? rangeMinZ : rangeMaxZ;
            for (final String tileId : zToTileBounds.get(sourceTransformZ).getTileIds()) {
                final TileSpec tileSpec = restartTileSpecCollection.getTileSpec(tileId);
                rowColumnToTransform.put(getRowAndColumnKey(tileSpec), tileSpec.getLastTransform());
            }

            // batch layers based on tile count ...
            final List<SectionData> rangeSectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                                rangeMinZ,
                                                                                                rangeMaxZ);
            final Map<Double, Long> zToTileCountMap = new HashMap<>();
            for (final SectionData sectionData : rangeSectionDataList) {
                final Double sectionZ = sectionData.getZ();
                long tileCount = sectionData.getTileCount();
                if (zToTileCountMap.containsKey(sectionZ)) {
                    tileCount += zToTileCountMap.get(sectionZ);
                }
                zToTileCountMap.put(sectionZ,tileCount);
            }

            final List<Double> batchMaxZValues = new ArrayList<>();
            long tileCount = 0;
            for (final Double z : zToTileCountMap.keySet().stream().sorted().collect(Collectors.toList())) {
                tileCount += zToTileCountMap.get(z);
                if ((tileCount > 20000) && (! z.equals(rangeMaxZ))) {
                    batchMaxZValues.add(z);
                    tileCount = 0;
                }
            }
            batchMaxZValues.add(rangeMaxZ);

            LOG.info("savePreAlignStacks: minZ is {}, batchMaxZValues are {}", rangeMinZ, batchMaxZValues);

            Double minZ = rangeMinZ;
            for (final Double maxZ : batchMaxZValues) {
                final ResolvedTileSpecCollection rangeTileSpecCollection =
                        renderDataClient.getResolvedTiles(parameters.stack, minZ, maxZ,
                                                          null,
                                                          null, null, null, null);

                for (final TileSpec tileSpec : rangeTileSpecCollection.getTileSpecs()) {
                    final String rowAndColumnKey = getRowAndColumnKey(tileSpec);
                    final TransformSpec transformSpec = rowColumnToTransform.get(rowAndColumnKey);
                    rangeTileSpecCollection.addTransformSpecToTile(tileSpec.getTileId(), transformSpec, REPLACE_LAST);
                }

                saveTargetTileSpecs(parameters.targetStack, rangeTileSpecCollection, false);

                minZ = maxZ;
            }

        }

        targetDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void saveTargetTileSpecs(final String stackName,
                                     final ResolvedTileSpecCollection tileSpecCollection,
                                     final boolean completeStackAfterSave)
            throws IOException {

        final int numberOfTileSpecs = tileSpecCollection.getTileCount();

        if (numberOfTileSpecs > 0) {

            LOG.info("saveTargetTileSpecs: saving {} tile specs to stack {}", numberOfTileSpecs, stackName);

            targetDataClient.setupDerivedStack(sourceStackMetaData, stackName);

            targetDataClient.saveResolvedTiles(tileSpecCollection, stackName, null);

            if (completeStackAfterSave) {
                targetDataClient.setStackState(stackName, StackMetaData.StackState.COMPLETE);
            }

        } else {
            LOG.info("saveTargetTileSpecs: no specs to save for stack {}", stackName);
        }

        LOG.info("saveTargetTileSpecs: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(RestartSolverClient.class);

}