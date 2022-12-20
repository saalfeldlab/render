package org.janelia.render.client;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ScriptUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;

/**
 * Java client for running TrakEM2 tile optimizer.
 *
 * @author Stephan Saalfeld
 * @author Eric Trautman
 */
public class Trakem2SolverPreAlignClient{

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for layers to be processed")
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for layers to be processed")
        public Double maxZ;

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
                names = "--samplesPerDimension",
                description = "Samples per dimension"
        )
        public Integer samplesPerDimension = 2;

        @Parameter(
                names = "--maxDistanceZ",
                description = "Exclude any match pairs with tiles that are further apart in z than this distance")
        public Double maxDistanceZ;

        @Parameter(
                names = "--maxLayersPerBatch",
                description = "Max number of layers to include in each pre-align batch (smaller numbers will reduce memory footprint)"
        )
        public Integer maxLayersPerBatch = 100;

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
                description = "Name for aligned result stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after processing",
                arity = 0)
        public boolean completeTargetStack = false;

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
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final Trakem2SolverPreAlignClient client = new Trakem2SolverPreAlignClient(parameters);

                client.run();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final RenderDataClient targetDataClient;

    private final Map<String, List<Double>> sectionIdToZMap;
    private final Map<Double, Set<String>> zToSectionIdsMap;
    private final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;

    private Trakem2SolverPreAlignClient(final Parameters parameters)
            throws IOException {

        parameters.initDefaultValues();

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.matchOwner,
                                                    parameters.matchCollection);

        this.sectionIdToZMap = new TreeMap<>();
        this.zToTileSpecsMap = new HashMap<>();

        if (parameters.targetStack == null) {
            this.targetDataClient = null;
        } else {
            this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                         parameters.targetOwner,
                                                         parameters.targetProject);

            final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
            targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
        }

        final List<SectionData> allSectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                          null,
                                                                                          null);

        Double minZForRun = parameters.minZ;
        Double maxZForRun = parameters.maxZ;

        if ((minZForRun == null) || (maxZForRun == null)) {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
            final StackStats stackStats = stackMetaData.getStats();
            if (stackStats != null) {
                final Bounds stackBounds = stackStats.getStackBounds();
                if (stackBounds != null) {
                    if (minZForRun == null) {
                        minZForRun = stackBounds.getMinZ();
                    }
                    if (maxZForRun == null) {
                        maxZForRun = stackBounds.getMaxZ();
                    }
                }
            }

            if ((minZForRun == null) || (maxZForRun == null)) {
                throw new IllegalArgumentException(
                        "Failed to derive min and/or max z values for stack " + parameters.stack +
                        ".  Stack may need to be completed.");
            }
        }

        this.zToSectionIdsMap = new HashMap<>(allSectionDataList.size());
        final Double minZ = minZForRun;
        final Double maxZ = maxZForRun;

        allSectionDataList.forEach(sd -> {
            final Double z = sd.getZ();
            if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0)) {
                final List<Double> zListForSection = sectionIdToZMap.computeIfAbsent(sd.getSectionId(),
                                                                                     zList -> new ArrayList<>());
                zListForSection.add(z);

                final Set<String> sectionIdsForZ = zToSectionIdsMap.computeIfAbsent(z,
                                                                                    sectionIdSet -> new HashSet<>());
                sectionIdsForZ.add(sd.getSectionId());
            }
        });

        if (zToSectionIdsMap.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any sections with the specified z values");
        }

    }

    private void run()
            throws IOException, NotEnoughDataPointsException, IllDefinedDataPointsException {

        LOG.info("run: entry");

        final HashMap<String, Tile<TranslationModel2D>> idToTileMap = new HashMap<>();

        final List<Double> orderedZValues = zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList());

        Set<String> previouslyAlignedTileIds = new HashSet<>();
        int layersInCurrentBatch = 0;

        for (final Double z : orderedZValues) {

            for (final String pGroupId : zToSectionIdsMap.get(z)) {
                final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId,
                                                                                           false);
                connectTiles(idToTileMap, matches);
            }

            layersInCurrentBatch++;

            if (layersInCurrentBatch == parameters.maxLayersPerBatch) {
                previouslyAlignedTileIds = preAlignAndSaveBatch(idToTileMap,
                                                                previouslyAlignedTileIds,
                                                                z,
                                                                false);
                layersInCurrentBatch = 0;
            }

        }

        final Double lastZ = orderedZValues.get(orderedZValues.size() - 1);
        preAlignAndSaveBatch(idToTileMap, previouslyAlignedTileIds, lastZ, true);

        if (parameters.completeTargetStack) {
            targetDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        }

        LOG.info("run: exit");
    }

    private void connectTiles(final HashMap<String, Tile<TranslationModel2D>> idToTileMap,
                              final List<CanvasMatches> matches)
            throws IOException {

        for (final CanvasMatches match : matches) {

            final String pId = match.getpId();
            final TileSpec pTileSpec = getTileSpec(match.getpGroupId(), pId);

            final String qGroupId = match.getqGroupId();
            final String qId = match.getqId();
            final TileSpec qTileSpec = getTileSpec(qGroupId, qId);

            if ((pTileSpec == null) || (qTileSpec == null)) {
                LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}",
                         pId, qId, parameters.stack);
                continue;
            }

            if (parameters.maxDistanceZ != null) {
                final double distanceZ = Math.abs(pTileSpec.getZ() - qTileSpec.getZ());
                if (distanceZ > parameters.maxDistanceZ) {
                    LOG.info("run: ignoring pair ({}, {}) with z distance {}", pId, qId, distanceZ);
                    continue;
                }
            }

            final Tile<TranslationModel2D> p =
                    idToTileMap.computeIfAbsent(pId,
                                                pTile -> buildTileFromSpec(pTileSpec));

            final Tile<TranslationModel2D> q =
                    idToTileMap.computeIfAbsent(qId,
                                                qTile -> buildTileFromSpec(qTileSpec));

            p.connect(q,
                      CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
        }
    }

    private Tile<TranslationModel2D> buildTileFromSpec(final TileSpec tileSpec) {

        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        final AffineModel2D lastTransform = (AffineModel2D)
                transformList.get(transformList.getList(null).size() - 1);
        //final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (parameters.samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (parameters.samplesPerDimension - 1.0);

        final TranslationModel2D model = new TranslationModel2D();

        try {
            ScriptUtil.fit(model, lastTransform, sampleWidth, sampleHeight, parameters.samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(model.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new Tile<>(model);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Set<String> preAlignAndSaveBatch(final Map<String, Tile<TranslationModel2D>> idToTileMap,
                                             final Set<String> previouslyAlignedTileIds,
                                             final Double z,
                                             final boolean isLastBatch)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException, IOException {

        final TileConfiguration tileConfig = new TileConfiguration();
        tileConfig.addTiles(idToTileMap.values());

        for (final String tileId : previouslyAlignedTileIds) {
            tileConfig.fixTile(idToTileMap.get(tileId));
        }

        final List<Tile<?>> unalignedTiles = tileConfig.preAlign();

        LOG.info("preAlignAndSaveBatch: for batch ending with z {}, {} out of {} tiles were aligned",
                 z, idToTileMap.size() - unalignedTiles.size(), idToTileMap.size());

        // save aligned tile specs for all prior layers
        final Set<Double> zToSave =
                zToTileSpecsMap.keySet().stream()
                        .filter(keyZ -> keyZ < z)
                        .collect(Collectors.toSet());

        if (isLastBatch) {

            // all remaining layers should be saved in the last batch
            zToSave.add(z);

            // if we have unaligned tiles in the last batch try to align them as much as possible
            if (unalignedTiles.size() > 0) {
                preAlignUnalignedTiles(unalignedTiles, idToTileMap);
            }

        }

        saveTargetStackTiles(idToTileMap, zToSave);

        final Runtime runtime = Runtime.getRuntime();
        final Set<String> keptAlignedTileIds = new HashSet<>();

        if (isLastBatch) {

            final double usedGb = (runtime.totalMemory() - runtime.freeMemory()) / 1_000_000_000.0;
            final double totalGb = runtime.totalMemory() / 1_000_000_000.0;

            LOG.info("preAlignAndSaveBatch: after processing last batch, memory used: {}G, total: {}G",
                     usedGb,
                     totalGb);

        } else {

            // if there are more batches, clean up data we no longer need ...
            final List<Tile> removedTiles = new ArrayList<>();
            final Set<String> unalignedTileIds = new HashSet<>();

            for (final Double savedZ : zToSave) {
                // remove tile spec collections for all saved layers
                final ResolvedTileSpecCollection tileSpecsForZ = zToTileSpecsMap.remove(savedZ);
                if (tileSpecsForZ != null) {

                    boolean foundUnalignedTile = false;

                    // remove mpicbg tiles (and their matches) for all saved layers
                    for (final TileSpec tileSpec : tileSpecsForZ.getTileSpecs()) {
                        final Tile tile = idToTileMap.remove(tileSpec.getTileId());
                        if (tile != null) {
                            if (unalignedTiles.contains(tile)) {
                                // put tile back into working set since it is still unconnected
                                unalignedTileIds.add(tileSpec.getTileId());
                                idToTileMap.put(tileSpec.getTileId(), tile);
                                foundUnalignedTile = true;
                            } else {
                                removedTiles.add(tile);
                            }
                        }
                    }

                    if (foundUnalignedTile) {
                        // put tile specs back into working set since one or more are still unconnected
                        zToTileSpecsMap.put(savedZ, tileSpecsForZ);
                    }

                }
            }

            for (final Tile keptOverlapTile : idToTileMap.values()) {
                removedTiles.forEach(keptOverlapTile::removeConnectedTile);
            }


            if (unalignedTileIds.size() > 0) {

                LOG.warn("preAlignAndSaveBatch: {} tiles could not be aligned, keeping them for later, tileIds are {}",
                         unalignedTileIds.size(), unalignedTileIds.stream().sorted().collect(Collectors.toList()));

                // ensure unaligned tile ids do not get fixed in next batch
                // by filtering them out of previously aligned set
                idToTileMap.keySet().stream()
                        .filter(tileId -> ! unalignedTileIds.contains(tileId))
                        .forEach(keptAlignedTileIds::add);
                keptAlignedTileIds.removeAll(unalignedTileIds);

            } else {
                keptAlignedTileIds.addAll(idToTileMap.keySet());
            }

            if (keptAlignedTileIds.size() == 0) {
                LOG.error("no previously aligned tiles remain for the next batch");
            }

            final double usedGb = (runtime.totalMemory() - runtime.freeMemory()) / 1_000_000_000.0;
            final double totalGb = runtime.totalMemory() / 1_000_000_000.0;

            LOG.info("preAlignAndSaveBatch: after clean-up, data for {} tiles in layers {} remain, memory used: {}G, total: {}G",
                     idToTileMap.size(),
                     zToTileSpecsMap.keySet().stream().sorted().collect(Collectors.toList()),
                     usedGb,
                     totalGb);

        }

        return keptAlignedTileIds;
    }

    @SuppressWarnings("rawtypes")
    private void preAlignUnalignedTiles(final List<Tile<?>> unalignedTiles,
                                        final Map<String, Tile<TranslationModel2D>> idToTileMap)
            throws NotEnoughDataPointsException, IllDefinedDataPointsException {

        final Tile connectedUnalignedTile =
                unalignedTiles.stream()
                        .filter(tile -> tile.getConnectedTiles().size() > 0)
                        .findFirst()
                        .orElse(null);

        if (connectedUnalignedTile == null) {

            LOG.info("preAlignUnalignedTiles: stopping recursion since none of the {} remaining tiles have connections",
                     unalignedTiles.size());

        } else {

            final String connectedTileId =
                    idToTileMap.keySet().stream()
                            .filter(tileId -> idToTileMap.get(tileId).equals(connectedUnalignedTile))
                            .findFirst()
                            .orElse(null);

            LOG.info("preAlignUnalignedTiles: fixing tile {} and running pre-align for {} tiles",
                     connectedTileId, unalignedTiles.size());

            final TileConfiguration tileConfig = new TileConfiguration();
            tileConfig.fixTile(connectedUnalignedTile);
            tileConfig.addTiles(unalignedTiles);

            final List<Tile<?>> remainingUnalignedTiles = tileConfig.preAlign();

            if (remainingUnalignedTiles.size() > 0) {
                if (remainingUnalignedTiles.size() < unalignedTiles.size()) {
                    preAlignUnalignedTiles(remainingUnalignedTiles, idToTileMap);
                } else {
                    LOG.info("preAlignUnalignedTiles: stopping recursion since nothing improved in this pass, {} tiles will be left in their original positions",
                             remainingUnalignedTiles.size());
                }
            } else {
                LOG.info("preAlignUnalignedTiles: no more remaining unaligned tiles - yay");
            }
        }

    }

    private void saveTargetStackTiles(final Map<String, Tile<TranslationModel2D>> idToTileMap,
                                      final Set<Double> zToSave)
            throws IOException {

        LOG.info("saveTargetStackTiles: entry, saving tile specs in {} layers", zToSave.size());

        for (final Double z : zToTileSpecsMap.keySet().stream().sorted().collect(Collectors.toList())) {

            if (zToSave.contains(z)) {

                final ResolvedTileSpecCollection resolvedTiles = zToTileSpecsMap.get(z);

                for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

                    final String tileId = tileSpec.getTileId();
                    final Tile<TranslationModel2D> tile = idToTileMap.get(tileId);

                    if (tile != null) {
                        resolvedTiles.addTransformSpecToTile(tileId,
                                                             getTransformSpec(tile.getModel()),
                                                             REPLACE_LAST);
                    }

                }

                if (resolvedTiles.getTileCount() > 0) {
                    targetDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);
                } else {
                    LOG.info("skipping tile spec save since no specs are left to save");
                }

            }

        }

        LOG.info("saveTargetStackTiles: exit");
    }

    private LeafTransformSpec getTransformSpec(final TranslationModel2D forModel) {
        final double[] m = new double[6];
        forModel.toArray(m);
        final String data = String.valueOf(m[0]) + ' ' + m[1] + ' ' + m[2] + ' ' + m[3] + ' ' + m[4] + ' ' + m[5];
        return new LeafTransformSpec(mpicbg.trakem2.transform.AffineModel2D.class.getName(), data);
    }

    private TileSpec getTileSpec(final String sectionId,
                                 final String tileId)
            throws IOException {

        TileSpec tileSpec = null;

        if (sectionIdToZMap.containsKey(sectionId)) {

            for (final Double z : sectionIdToZMap.get(sectionId)) {

                if (! zToTileSpecsMap.containsKey(z)) {

                    final ResolvedTileSpecCollection resolvedTiles =
                            renderDataClient.getResolvedTiles(parameters.stack, z);

                    // check for accidental use of rough aligned stack ...
                    resolvedTiles.getTileSpecs().forEach(ts -> {
                        if (ts.getLastTransform() instanceof ReferenceTransformSpec) {
                            throw new IllegalStateException(
                                    "last transform for tile " + ts.getTileId() +
                                    " is a reference transform which will break this fragile client, " +
                                    "make sure --stack is not a rough aligned stack ");
                        }
                    });

                    resolvedTiles.resolveTileSpecs();

                    zToTileSpecsMap.put(z, resolvedTiles);
                }

                final ResolvedTileSpecCollection resolvedTileSpecCollection = zToTileSpecsMap.get(z);
                tileSpec = resolvedTileSpecCollection.getTileSpec(tileId);

                if (tileSpec != null) {
                    break;
                }
            }
            
        }

        return tileSpec;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Trakem2SolverPreAlignClient.class);

}
