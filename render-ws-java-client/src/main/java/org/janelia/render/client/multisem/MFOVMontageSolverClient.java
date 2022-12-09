package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.Trakem2SolverClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

/**
 * Adaptation of {@link Trakem2SolverClient} that stitches MFOVs "in isolation" by solving each MFOV in a z-layer
 * independently using only matches from SFOV tile pairs where both tiles are in the same MFOV.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("rawtypes")
public class MFOVMontageSolverClient {

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

        public String getMatchOwner() {
            return matchOwner == null ? renderWeb.owner : matchOwner;
        }

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

        @Parameter(
                names = "--startLambda",
                description = "Starting lambda for optimizer.  " +
                              "Optimizer loops through lambdas 1.0, 0.5, 0.1. 0.01.  " +
                              "If you know your starting alignment is good, " +
                              "set this to one of the smaller values to improve performance."
        )
        public Double startLambda = 0.01;

        @Parameter(
                names = "--optimizerLambdas",
                description = "Explicit optimizer lambda values.",
                variableArity = true
        )
        public List<Double> optimizerLambdas;

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

        @Parameter(
                names = "--z",
                description = "Z value for MFOV to solve",
                variableArity = true,
                required = true)
        public List<Double> zValues;

        @Parameter(
                names = "--mfov",
                description = "Multi-field-of-view identifier <slab number>_<mFOV number> (e.g. 001_000006) to solve",
                variableArity = true,
                required = true
        )
        public List<String> mFOVList;

        public Parameters() {
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MFOVMontageSolverClient client = new MFOVMontageSolverClient(parameters);
                client.run();
            }
        };

        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;

    private final Set<String> mFOVSet;
    private final List<String> matchGroupList;
    private final Map<String, List<Double>> sectionIdToZMap;
    private ResolvedTileSpecCollection resolvedTilesForCurrentZ;

    public MFOVMontageSolverClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.getMatchOwner(),
                                                    parameters.matchCollection);

        this.sectionIdToZMap = new TreeMap<>();

        this.mFOVSet = new HashSet<>(parameters.mFOVList);

        final ZFilter zFilter = new ZFilter(null, null, parameters.zValues);
        final List<SectionData> allSectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                          null,
                                                                                          null);
        this.matchGroupList = new ArrayList<>(allSectionDataList.size());
        this.matchGroupList.addAll(
                allSectionDataList.stream()
                        .filter(sectionData -> zFilter.accept(sectionData.getZ()))
                        .map(SectionData::getSectionId)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList()));

        if (this.matchGroupList.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any sections with the specified z values");
        }

        allSectionDataList.forEach(sd -> {
            final Double z = sd.getZ();
            if (zFilter.accept(z)) {
                final List<Double> zListForSection = sectionIdToZMap.computeIfAbsent(sd.getSectionId(),
                                                                                     zList -> new ArrayList<>());
                zListForSection.add(sd.getZ());
            }
        });

        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        renderDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
    }

    public void run()
            throws IOException, ExecutionException, InterruptedException {

        LOG.info("run: entry");

        final List<String> sortedMFOVList = mFOVSet.stream().sorted().collect(Collectors.toList());

        for (final String groupId : matchGroupList) {

            LOG.info("run: connecting tiles with groupId {}", groupId);

            final Double z = new Double(groupId);
            resolvedTilesForCurrentZ = renderDataClient.getResolvedTiles(parameters.stack, z);

            // get rid of tile specs that aren't in the mFOVs we are stitching
            final Set<String> tileIdsToKeep = resolvedTilesForCurrentZ.getTileSpecs()
                    .stream()
                    .map(TileSpec::getTileId)
                    .filter(id -> mFOVSet.contains(Utilities.getMFOVForTileId(id)))
                    .collect(Collectors.toSet());
            resolvedTilesForCurrentZ.removeDifferentTileSpecs(tileIdsToKeep);
            resolvedTilesForCurrentZ.resolveTileSpecs();

            final Map<String, Set<String>> mFOVToTileIdMap = new HashMap<>();
            final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap = new HashMap<>();

            final List<CanvasMatches> matches = matchDataClient.getMatchesWithinGroup(groupId, false);
            for (final CanvasMatches match : matches) {

                final String pId = match.getpId();
                final String qId = match.getqId();

                final String pMFOV = Utilities.getMFOVForTileId(pId);
                final String qMFOV = Utilities.getMFOVForTileId(qId);

                if (pMFOV.equals(qMFOV) && mFOVSet.contains(pMFOV)) {

                    final TileSpec pTileSpec = resolvedTilesForCurrentZ.getTileSpec(pId);
                    final TileSpec qTileSpec = resolvedTilesForCurrentZ.getTileSpec(qId);

                    if ((pTileSpec == null) || (qTileSpec == null)) {
                        LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}",
                                 pId, qId, parameters.stack);
                        continue;
                    }

                    final Set<String> mFOVTileIds = mFOVToTileIdMap.computeIfAbsent(pMFOV, k -> new HashSet<>());
                    mFOVTileIds.add(pId);
                    mFOVTileIds.add(qId);

                    final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> p =
                            idToTileMap.computeIfAbsent(
                                    pId,
                                    pTile -> Trakem2SolverClient.buildTileFromSpec(pTileSpec,
                                                                                   parameters.samplesPerDimension,
                                                                                   ModelType.TRANSLATION));

                    final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> q =
                            idToTileMap.computeIfAbsent(
                                    qId,
                                    qTile -> Trakem2SolverClient.buildTileFromSpec(qTileSpec,
                                                                                   parameters.samplesPerDimension,
                                                                                   ModelType.TRANSLATION));

                    p.connect(q,
                              CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
                }
            }

            for (final String mFOV : sortedMFOVList) {

                final TileConfiguration tileConfig = new TileConfiguration();

                final Set<String> mFOVTileIds = mFOVToTileIdMap.get(mFOV);
                mFOVTileIds.forEach(tileId -> tileConfig.addTile(idToTileMap.get(tileId)));

                LOG.info("run: optimizing {} tiles for mFOV {} in z {}", mFOVTileIds.size(), mFOV, z);

                final List<Double> lambdaValues = Trakem2SolverClient.buildLambdaList(parameters.optimizerLambdas,
                                                                                      parameters.startLambda);
                for (final double lambda : lambdaValues) {

                    for (final Tile tile : tileConfig.getTiles()) {
                        ((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
                    }

                    // tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);

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
                                                  1);
                }
            }

            saveTargetStackTiles(idToTileMap, z);
        }

        if (parameters.completeTargetStack) {
            renderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        }

        LOG.info("run: exit");
    }

    private void saveTargetStackTiles(final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap,
                                      final Double z)
            throws IOException {

        LOG.info("saveTargetStackTiles: entry, z={}", z);

        final Set<String> tileIdsToRemove = new HashSet<>();

        for (final TileSpec tileSpec : resolvedTilesForCurrentZ.getTileSpecs()) {

            final String tileId = tileSpec.getTileId();
            final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> tile = idToTileMap.get(tileId);

            if (tile == null) {
                tileIdsToRemove.add(tileId);
            } else {
                resolvedTilesForCurrentZ.addTransformSpecToTile(tileId,
                                                                Trakem2SolverClient.getTransformSpec(tile.getModel()),
                                                                REPLACE_LAST);
            }

        }

        if (tileIdsToRemove.size() > 0) {
            LOG.info("removed {} unaligned tile specs from target collection", tileIdsToRemove.size());
            resolvedTilesForCurrentZ.removeTileSpecs(tileIdsToRemove);
        }

        if (resolvedTilesForCurrentZ.getTileCount() > 0) {
            renderDataClient.saveResolvedTiles(resolvedTilesForCurrentZ, parameters.targetStack, z);
        } else {
            LOG.info("skipping tile spec save since no specs are left to save");
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageSolverClient.class);

}
