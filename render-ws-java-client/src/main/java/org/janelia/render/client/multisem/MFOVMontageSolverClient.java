package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.UnconnectedMFOVPairsForStack;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.Trakem2SolverClient;
import org.janelia.render.client.parameter.CommandLineParameters;
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

        @Parameter(
                names = "--baseDataUrl",
                description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
                required = true)
        public String baseDataUrl;

        @Parameter(
                names = "--unconnectedMFOVPairsFile",
                description = "File with unconnected MFOV pairs",
                variableArity = true,
                required = true)
        public List<String> unconnectedMFOVPairsFiles;

        @Parameter(
                names = "--completeMontageStacks",
                description = "Complete the montage stacks after processing",
                arity = 0)
        public boolean completeMontageStacks = false;

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

                final MFOVMontageSolverClient client = new MFOVMontageSolverClient();
                client.loadAndSolveUnconnectedMFOVs(parameters);
            }
        };

        clientRunner.run();
    }

    public void loadAndSolveUnconnectedMFOVs(final Parameters parameters) throws IOException {
        if (parameters.unconnectedMFOVPairsFiles.size() > 0) {

            final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks =
                    UnconnectedMFOVPairsForStack.load(parameters.unconnectedMFOVPairsFiles.get(0));

            // add more pairs if multiple files have been specified
            // note: pairs for same stack are not merged back together here because it is not worth the trouble
            for (int i = 1; i < parameters.unconnectedMFOVPairsFiles.size(); i++) {
                final List<UnconnectedMFOVPairsForStack> morePairs =
                        UnconnectedMFOVPairsForStack.load(parameters.unconnectedMFOVPairsFiles.get(i));
                unconnectedMFOVsForAllStacks.addAll(morePairs);
            }

            for (final UnconnectedMFOVPairsForStack pairs : unconnectedMFOVsForAllStacks) {
                solveUnconnectedStack(parameters.baseDataUrl,
                                      pairs,
                                      parameters.completeMontageStacks);
            }

        } else {
            LOG.warn("loadAndSolveUnconnectedMFOVs: nothing to do!");
        }
    }

    public static void solveUnconnectedStack(final String baseDataUrl,
                                             final UnconnectedMFOVPairsForStack unconnectedMFOVPairsForStack,
                                             final boolean completeMontageStacks)
            throws IOException {

        final StackId stackId = unconnectedMFOVPairsForStack.getRenderStackId();

        LOG.info("solveUnconnectedStack: entry, for {}", stackId);

        final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl,
                                                                       stackId.getOwner(),
                                                                       stackId.getProject());
        final String stack = stackId.getStack();
        final String montageStack = unconnectedMFOVPairsForStack.getmFOVMontageStackName();

        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(stack);
        renderDataClient.setupDerivedStack(sourceStackMetaData, montageStack);

        final MatchCollectionId matchCollectionId = unconnectedMFOVPairsForStack.getMatchCollectionId();
        final RenderDataClient matchDataClient = new RenderDataClient(baseDataUrl,
                                                                      matchCollectionId.getOwner(),
                                                                      matchCollectionId.getName());

        final List<LayerMFOV> unconnectedMFOVs = unconnectedMFOVPairsForStack.getOrderedDistinctUnconnectedMFOVs();

        final Map<Double, Set<String>> zToMFOVSet = new HashMap<>();
        for (final LayerMFOV layerMFOV : unconnectedMFOVs) {
            final Set<String> mFOVSet = zToMFOVSet.computeIfAbsent(layerMFOV.getZ(), k -> new HashSet<>());
            mFOVSet.add(layerMFOV.getName());
        }

        for (final double z : zToMFOVSet.keySet().stream().sorted().collect(Collectors.toList())) {

            LOG.info("solveUnconnectedStack: connecting tiles for z {}", z);

            final Set<String> mFOVSet = zToMFOVSet.get(z);
            final List<String> sortedMFOVList = mFOVSet.stream().sorted().collect(Collectors.toList());

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
            // get rid of tile specs that aren't in the mFOVs we are stitching
            final Set<String> tileIdsToKeep = resolvedTiles.getTileSpecs()
                    .stream()
                    .map(TileSpec::getTileId)
                    .filter(id -> mFOVSet.contains(Utilities.getMFOVForTileId(id)))
                    .collect(Collectors.toSet());
            resolvedTiles.removeDifferentTileSpecs(tileIdsToKeep);
            resolvedTiles.resolveTileSpecs();

            final Map<String, Set<String>> mFOVToTileIdMap = new HashMap<>();

            final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap =
                    buildTileDataForZ(z,
                                      stack,
                                      resolvedTiles,
                                      matchDataClient,
                                      mFOVSet,
                                      mFOVToTileIdMap);

            solveTileData(z,
                          sortedMFOVList,
                          mFOVToTileIdMap,
                          idToTileMap);

            saveTargetStackTileSpecs(montageStack,
                                     z,
                                     idToTileMap,
                                     resolvedTiles,
                                     renderDataClient,
                                     matchCollectionId.getName());
        }

        if (completeMontageStacks) {
            renderDataClient.setStackState(montageStack, StackMetaData.StackState.COMPLETE);
        }

        LOG.info("solveUnconnectedStack: exit, for {}", stackId);
    }

    private static Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> buildTileDataForZ(
            final double z,
            final String stack,
            final ResolvedTileSpecCollection resolvedTiles,
            final RenderDataClient matchDataClient,
            final Set<String> mFOVSet,
            final Map<String, Set<String>> mFOVToTileIdMap) throws IOException {

        final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap =
                new HashMap<>();

        final String groupId = String.valueOf(z);

        final List<CanvasMatches> matches = matchDataClient.getMatchesWithinGroup(groupId, false);
        for (final CanvasMatches match : matches) {

            final String pId = match.getpId();
            final String qId = match.getqId();

            final String pMFOV = Utilities.getMFOVForTileId(pId);
            final String qMFOV = Utilities.getMFOVForTileId(qId);

            if (pMFOV.equals(qMFOV) && mFOVSet.contains(pMFOV)) {

                final TileSpec pTileSpec = resolvedTiles.getTileSpec(pId);
                final TileSpec qTileSpec = resolvedTiles.getTileSpec(qId);

                if ((pTileSpec == null) || (qTileSpec == null)) {
                    LOG.info("solveUnconnectedStack: ignoring pair ({}, {}) because one or both tiles are missing from stack {}",
                             pId, qId, stack);
                    continue;
                }

                final Set<String> mFOVTileIds = mFOVToTileIdMap.computeIfAbsent(pMFOV, k -> new HashSet<>());
                mFOVTileIds.add(pId);
                mFOVTileIds.add(qId);

                final int samplesPerDimension = 2;
                final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> p =
                        idToTileMap.computeIfAbsent(
                                pId,
                                pTile -> Trakem2SolverClient.buildTileFromSpec(pTileSpec,
                                                                               samplesPerDimension,
                                                                               ModelType.TRANSLATION));

                final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> q =
                        idToTileMap.computeIfAbsent(
                                qId,
                                qTile -> Trakem2SolverClient.buildTileFromSpec(qTileSpec,
                                                                               samplesPerDimension,
                                                                               ModelType.TRANSLATION));

                p.connect(q,
                          CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
            }
        }
        return idToTileMap;
    }

    private static void solveTileData(final double z,
                                      final List<String> sortedMFOVList,
                                      final Map<String, Set<String>> mFOVToTileIdMap,
                                      final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap)
            throws IOException {
        for (final String mFOV : sortedMFOVList) {

            final TileConfiguration tileConfig = new TileConfiguration();

            final Set<String> mFOVTileIds = mFOVToTileIdMap.get(mFOV);
            mFOVTileIds.forEach(tileId -> tileConfig.addTile(idToTileMap.get(tileId)));

            LOG.info("solveUnconnectedStack: optimizing {} tiles for mFOV {} in z {}", mFOVTileIds.size(), mFOV, z);

            final double lambda = 0.01; // stage SFOV tile positions are very close to correct, so use small lambda
            for (final Tile tile : tileConfig.getTiles()) {
                ((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
            }

            final int maxPlateauWidth = 200;
            final ErrorStatistic observer = new ErrorStatistic(maxPlateauWidth + 1);
            final float damp = 1.0f;
            try {
                TileUtil.optimizeConcurrently(observer,
                                              200,
                                              2000,
                                              maxPlateauWidth,
                                              damp,
                                              tileConfig,
                                              tileConfig.getTiles(),
                                              tileConfig.getFixedTiles(),
                                              1);
            } catch (final Exception e) {
                throw new IOException("failed to optimize MFOV " + mFOV + " in z " + z, e);
            }
        }
    }

    private static void saveTargetStackTileSpecs(final String montageStack,
                                                 final Double z,
                                                 final Map<String, Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>>> idToTileMap,
                                                 final ResolvedTileSpecCollection resolvedTiles,
                                                 final RenderDataClient renderDataClient,
                                                 final String matchCollection)
            throws IOException {

        LOG.info("saveTargetStackTiles: entry, z={}", z);

        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

            final String tileId = tileSpec.getTileId();
            final Tile<InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D>> tile = idToTileMap.get(tileId);

            if (tile == null) {
                throw new IOException("Tile " + tileId + " was dropped because " + matchCollection +
                                      " does not contain matches for it with any other tiles in MFOV " +
                                      Utilities.getMFOVForTileId(tileId) +
                                      ".  Make sure montage match patching has been run for " +
                                      matchCollection + " (or specify a different collection).");
            }

            resolvedTiles.addTransformSpecToTile(tileId,
                                                 Trakem2SolverClient.getTransformSpec(tile.getModel()),
                                                 REPLACE_LAST);
        }

        if (resolvedTiles.getTileCount() > 0) {
            renderDataClient.saveResolvedTiles(resolvedTiles, montageStack, z);
        } else {
            LOG.info("skipping tile spec save since no specs are left to save");
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVMontageSolverClient.class);
}
