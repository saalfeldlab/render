package org.janelia.render.client;

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
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;

import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.WarpedTileSpecValidator;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

/**
 * Java client for running TrakEM2 tile optimizer.
 *
 * @author Stephan Saalfeld
 * @author Eric Trautman
 */
public class TileOptimizerClient {

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
                description = "Minimum (split) Z value for layers to be processed",
                required = true)
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum (split) Z value for layers to be processed",
                required = true)
        public Double maxZ;

        @Parameter(
                names = "--matchCollection",
                description = "Name of match collection for tiles",
                required = true
        )
        public String matchCollection;

        @Parameter(
                names = "--matchOwner",
                description = "Owner of match collection for tiles (default is owner)"
        )
        public String matchOwner;

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
                names = "--lambda",
                description = "Lambda"
        )
        public Double lambda = 0.01; // 1, 0.5, 0.1. 0.01

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

        @Parameter(
                names = "--mergedZ",
                description = "Z value for all aligned tiles (if omitted, original split z values are kept)"
        )
        public Double mergedZ;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after processing",
                arity = 0)
        public boolean completeTargetStack = false;

        public Parameters() {
        }

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
                            "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                            "--owner", "flyTEM",
                            "--project", "FAFB_montage",
                            "--stack", "split_0271_montage",
                            "--targetStack", "split_0271_optimized_montage",
                            "--completeTargetStack",
                            "--matchCollection", "FAFB_montage_fix",
                            "--minZ", "100270",
                            "--maxZ", "100272"
//                            ,"--mergedZ", "271"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final TileOptimizerClient client = new TileOptimizerClient(parameters);

                client.runOptimizer();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final RenderDataClient targetDataClient;

    private final List<String> pGroupList;
    private final Map<String, Double> sectionIdToZMap;
    private final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;
    private int totalTileCount;

    private TileOptimizerClient(final Parameters parameters)
            throws IOException {

        parameters.initDefaultValues();

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.matchOwner,
                                                    parameters.matchCollection);

        this.sectionIdToZMap = new TreeMap<>();
        this.zToTileSpecsMap = new HashMap<>();
        this.totalTileCount = 0;

        if (parameters.targetStack == null) {
            this.targetDataClient = null;
        } else {
            this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                         parameters.targetOwner,
                                                         parameters.targetProject);

            final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
            targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
        }

        final ZFilter zFilter = new ZFilter(parameters.minZ,
                                            parameters.maxZ,
                                            null);
        final List<SectionData> allSectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                                          null,
                                                                                          null);
        this.pGroupList = new ArrayList<>(allSectionDataList.size());
        this.pGroupList.addAll(
                allSectionDataList.stream()
                        .filter(sectionData -> zFilter.accept(sectionData.getZ()))
                        .map(SectionData::getSectionId)
                        .collect(Collectors.toList()));

        if (this.pGroupList.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any sections with the specified z values");
        }

        allSectionDataList.forEach(sd -> sectionIdToZMap.put(sd.getSectionId(), sd.getZ()));
    }

    private void runOptimizer()
            throws IOException, NotEnoughDataPointsException, IllDefinedDataPointsException {

        LOG.info("runOptimizer: entry");

        final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>>> idToTileMap = new HashMap<>();

        for (final String pGroupId : pGroupList) {

            LOG.info("runOptimizer: connecting tiles with pGroupId {}", pGroupId);

            final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId);
            for (final CanvasMatches match : matches) {

                final String pId = match.getpId();
                final TileSpec pTileSpec = getTileSpec(pGroupId, pId);

                final String qGroupId = match.getqGroupId();
                final String qId = match.getqId();
                final TileSpec qTileSpec = getTileSpec(qGroupId, qId);

                if ((pTileSpec == null) || (qTileSpec == null)) {
                    LOG.info("runOptimizer: ignoring pair ({}, {}) because one or both tiles are missing from stack {}",
                             pId, qId, parameters.stack);
                    continue;
                }

                final Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> p =
                        idToTileMap.computeIfAbsent(pId,
                                                    pTile -> getTile(pTileSpec));

                final Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> q =
                        idToTileMap.computeIfAbsent(qId,
                                                    qTile -> getTile(qTileSpec));

                p.connect(q,
                          CanvasFeatureMatchResult.convertMatchesToPointMatchList(match.getMatches()));
            }
        }

        final TileConfiguration tileConfig = new TileConfiguration();
        tileConfig.addTiles(idToTileMap.values());

        LOG.info("runOptimizer: optimizing {} tiles", idToTileMap.size());

        for (final double lambda : new double[]{1, 0.5, 0.1, 0.01}) {

            for (final Tile tile : idToTileMap.values())
                ((InterpolatedAffineModel2D)tile.getModel()).setLambda(lambda);

            tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);
            
        }

        if (parameters.targetStack == null) {

            for (final String tileId : idToTileMap.keySet()) {
                final Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> tile = idToTileMap.get(tileId);
                final InterpolatedAffineModel2D model = tile.getModel();
                LOG.info("tile {} model is {}", tileId, model.createAffineModel2D());
            }

        } else {

            saveTargetStackTiles(idToTileMap);

        }

        LOG.info("runOptimizer: exit");
    }

    private void saveTargetStackTiles(final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>>> idToTileMap)
            throws IOException {

        LOG.info("saveTargetStackTiles: entry");

        for (final ResolvedTileSpecCollection resolvedTiles : zToTileSpecsMap.values()) {

            final Set<String> tileIdsToRemove = new HashSet<>();

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

                final String tileId = tileSpec.getTileId();
                final Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> tile = idToTileMap.get(tileId);

                if (tile == null) {
                    tileIdsToRemove.add(tileId);
                } else {
                    resolvedTiles.addTransformSpecToTile(tileId,
                                                         getTransformSpec(tile.getModel()),
                                                         REPLACE_LAST);
                }

                if (parameters.mergedZ != null) {
                    tileSpec.setZ(parameters.mergedZ);
                }

            }

            if (tileIdsToRemove.size() > 0) {
                LOG.info("removed {} unaligned tile specs from target collection", tileIdsToRemove.size());
                resolvedTiles.removeTileSpecs(tileIdsToRemove);
            }

            targetDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);
        }

        LOG.info("saveTargetStackTiles: saved tiles for all split sections");

        if (parameters.completeTargetStack) {
            targetDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        }

        LOG.info("saveTargetStackTiles: exit");
    }

    private LeafTransformSpec getTransformSpec(final InterpolatedAffineModel2D forModel) {
        final double[] m = new double[6];
        forModel.createAffineModel2D().toArray(m);
        final String data = String.valueOf(m[0]) + ' ' + m[1] + ' ' + m[2] + ' ' + m[3] + ' ' + m[4] + ' ' + m[5];
        return new LeafTransformSpec(mpicbg.trakem2.transform.AffineModel2D.class.getName(), data);
    }

    private TileSpec getTileSpec(final String sectionId,
                                 final String tileId)
            throws IOException {

        final Double z = sectionIdToZMap.get(sectionId);

        if (! zToTileSpecsMap.containsKey(z)) {

            if (totalTileCount > 100000) {
                throw new IllegalArgumentException("More than 100000 tiles need to be loaded - please reduce z values");
            }

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            resolvedTiles.resolveTileSpecs();
            zToTileSpecsMap.put(z, resolvedTiles);
            totalTileCount += resolvedTiles.getTileCount();
        }

        final ResolvedTileSpecCollection resolvedTileSpecCollection = zToTileSpecsMap.get(z);

        return resolvedTileSpecCollection.getTileSpec(tileId);
    }

    private Tile<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> getTile(final TileSpec tileSpec) {

        // TODO: make samplesPerDimension a parameter?
        final int samplesPerDimension = 2;

        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        final AffineModel2D lastTransform = (AffineModel2D) transformList.get(transformList.getList(null).size() - 1);
        final AffineModel2D copy = lastTransform.copy();

        final double scaleX = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double scaleY = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        final RigidModel2D rigidModel = WarpedTileSpecValidator.sampleRigidModel(tileSpec.getTileId(),
                                                                                 copy,
                                                                                 scaleX,
                                                                                 scaleY,
                                                                                 samplesPerDimension);

//        System.out.println("rigid: " + rigidModel);
//        System.out.println("copy: " + copy);

        return new Tile<>(new InterpolatedAffineModel2D<>(copy,
                                                          rigidModel,
                                                          parameters.lambda));
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileOptimizerClient.class);

}
