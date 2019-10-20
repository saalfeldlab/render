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
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;

import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ScriptUtil;
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
public class Trakem2SolverClient<B extends Model< B > & Affine2D< B >> {

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
                description = "Minimum (split) Z value for layers to be processed")
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum (split) Z value for layers to be processed")
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

        @Parameter(
                names = "--startLambda",
                description = "Starting lambda for optimizer.  " +
                              "Optimizer loops through lambdas 1.0, 0.5, 0.1. 0.01.  " +
                              "If you know your starting alignment is good, " +
                              "set this to one of the smaller values to improve performance."
        )
        public Double startLambda = 1.0;

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

        @Parameter(names = "--threads", description = "Number of threads to be used")
        public int numberOfThreads = 1;

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
                            "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                            "--owner", "flyTEM",
                            "--project", "FAFB_montage",
                            "--stack", "check_923_split_rough",
                            "--targetStack", "check_923_merged_test",
                            "--threads", "2",
                            "--completeTargetStack",
                            "--matchCollection", "FAFB_montage_fix_test",
//                            "--minZ", "100270",
//                            "--maxZ", "100272",
                            "--regularizerModelType", "RIGID"
                            ,"--mergedZ", "923"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final Trakem2SolverClient client = new Trakem2SolverClient(parameters);

                client.run();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final RenderDataClient targetDataClient;

    private final List<String> pGroupList;
    private final Map<String, List<Double>> sectionIdToZMap;
    private final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;
    private int totalTileCount;

    private Trakem2SolverClient(final Parameters parameters)
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
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList()));

        if (this.pGroupList.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any sections with the specified z values");
        }

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

        final Double minZ = minZForRun;
        final Double maxZ = maxZForRun;

        allSectionDataList.forEach(sd -> {
            final Double z = sd.getZ();
            if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0)) {
                final List<Double> zListForSection = sectionIdToZMap.computeIfAbsent(sd.getSectionId(),
                                                                                     zList -> new ArrayList<>());
                zListForSection.add(sd.getZ());
            }
        });
    }

    private void run()
            throws IOException, ExecutionException, InterruptedException {

        LOG.info("run: entry");

        final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();

        for (final String pGroupId : pGroupList) {

            LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

            final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);
            for (final CanvasMatches match : matches) {

                final String pId = match.getpId();
                final TileSpec pTileSpec = getTileSpec(pGroupId, pId);

                final String qGroupId = match.getqGroupId();
                final String qId = match.getqId();
                final TileSpec qTileSpec = getTileSpec(qGroupId, qId);

                if ((pTileSpec == null) || (qTileSpec == null)) {
                    LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}",
                             pId, qId, parameters.stack);
                    continue;
                }

                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p =
                        idToTileMap.computeIfAbsent(pId,
                                                    pTile -> buildTileFromSpec(pTileSpec));

                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> q =
                        idToTileMap.computeIfAbsent(qId,
                                                    qTile -> buildTileFromSpec(qTileSpec));

                p.connect(q,
                          CanvasFeatureMatchResult.convertMatchesToPointMatchList(match.getMatches()));
            }
        }

        final TileConfiguration tileConfig = new TileConfiguration();
        tileConfig.addTiles(idToTileMap.values());

        LOG.info("run: optimizing {} tiles", idToTileMap.size());

        final double[] lambdaValues = new double[] { 1, 0.5, 0.1, 0.01 };

        for (final double lambda : lambdaValues) {
            if (lambda <= parameters.startLambda) {

                for (final Tile tile : idToTileMap.values()) {
                    ((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
                }

                // tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);

                final ErrorStatistic observer = new ErrorStatistic(parameters.maxPlateauWidth + 1 );
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
        }

        if (parameters.targetStack == null) {

            for (final String tileId : idToTileMap.keySet()) {
                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
                final InterpolatedAffineModel2D model = tile.getModel();
                LOG.info("tile {} model is {}", tileId, model.createAffineModel2D());
            }

        } else {

            saveTargetStackTiles(idToTileMap);

        }

        LOG.info("run: exit");
    }

    private Tile<InterpolatedAffineModel2D<AffineModel2D, B>> buildTileFromSpec(final TileSpec tileSpec) {

        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        final AffineModel2D lastTransform = (AffineModel2D)
                transformList.get(transformList.getList(null).size() - 1);
        final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (parameters.samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (parameters.samplesPerDimension - 1.0);

        final B regularizer = parameters.regularizerModelType.getInstance();

        try {
            ScriptUtil.fit(regularizer, lastTransformCopy, sampleWidth, sampleHeight, parameters.samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(regularizer.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new Tile<>(new InterpolatedAffineModel2D<>(lastTransformCopy,
                                                          regularizer,
                                                          parameters.startLambda)); // note: lambda gets reset during optimization loops
    }

    private void saveTargetStackTiles(final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap)
            throws IOException {

        LOG.info("saveTargetStackTiles: entry");

        for (final ResolvedTileSpecCollection resolvedTiles : zToTileSpecsMap.values()) {

            final Set<String> tileIdsToRemove = new HashSet<>();

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

                final String tileId = tileSpec.getTileId();
                final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);

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

            if (resolvedTiles.getTileCount() > 0) {
                targetDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);
            } else {
                LOG.info("skipping tile spec save since no specs are left to save");
            }

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

        TileSpec tileSpec = null;

        for (final Double z : sectionIdToZMap.get(sectionId)) {

            if (! zToTileSpecsMap.containsKey(z)) {

                if (totalTileCount > 100000) {
                    throw new IllegalArgumentException("More than 100000 tiles need to be loaded - please reduce z values");
                }

                final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

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
                totalTileCount += resolvedTiles.getTileCount();
            }

            final ResolvedTileSpecCollection resolvedTileSpecCollection = zToTileSpecsMap.get(z);
            tileSpec = resolvedTileSpecCollection.getTileSpec(tileId);

            if (tileSpec != null) {
                break;
            }
        }

        return tileSpec;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Trakem2SolverClient.class);

}
