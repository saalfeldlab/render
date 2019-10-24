package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.*;
import net.imglib2.RealPoint;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.*;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Java client for running TrakEM2 tile optimizer.
 *
 * @author Stephan Saalfeld
 * @author Eric Trautman
 */
public class PointMatchDisplacementClient<B extends Model< B > & Affine2D< B >> {

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
                            "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_33m",
                            "--project", "Sec29",
                            "--stack", "v1_1_affine_1_12824",
                            "--threads", "1",
                            "--completeTargetStack",
                            "--matchCollection", "Sec29_v1",
                            "--minZ", "10250",
                            "--maxZ", "10262",
                            "--regularizerModelType", "RIGID"
                            ,"--mergedZ", "923"
//                            "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                            "--owner", "hessh",
//                            "--project", "Z1217_19m_VNC",
//                            "--stack", "Sec26_v1_affine_1_9604",
//                            "--threads", "1",
//                            "--completeTargetStack",
//                            "--matchCollection", "Z127_19m_VNC_Sec26_v1",
//                            "--minZ", "8250",
//                            "--maxZ", "8262",
//                            "--regularizerModelType", "RIGID"
//                            ,"--mergedZ", "923"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final PointMatchDisplacementClient client = new PointMatchDisplacementClient(parameters);

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

    private PointMatchDisplacementClient(final Parameters parameters)
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

        for (final String pGroupId : pGroupList) {// loop over Z-coords

            LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

            final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

            LOG.info("run: num tile matches {}", matches.size());

            List<Double> dists = new ArrayList<>();
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

                final CoordinateTransformList<CoordinateTransform> pModel = pTileSpec.getTransformList();
                final CoordinateTransformList<CoordinateTransform> qModel = qTileSpec.getTransformList();

                Matches pairMatches = match.getMatches();
                List<RealPoint> pList = pairMatches.getPList();
                List<RealPoint> qList = pairMatches.getQList();

                double[] pLocation = new double[3];
                double[] qLocation = new double[3];

                double matchDiff = 0;
                for( int matchId = 0; matchId < pList.size(); ++matchId ) {
                    pList.get(matchId).localize(pLocation);
                    qList.get(matchId).localize(qLocation);
                    double[] pTransformed = pModel.apply(pLocation);
                    double[] qTransformed = qModel.apply(qLocation);

                    dists.add(dist(pTransformed, qTransformed));
                }
            }

            DoubleSummaryStatistics stats = dists.stream().mapToDouble(Double::valueOf).summaryStatistics();
            double avg = stats.getAverage();
            double stdDev = Math.sqrt( dists.stream().map(x -> Math.pow(x - avg, 2)).mapToDouble(Double::valueOf).sum() / stats.getCount() );

            LOG.info("run: tile {} has a displacement average of {} and standard deviation of {}", pGroupId, avg, stdDev);
        }

        LOG.info("run: exit");
    }

    private double dist(double[] a, double[] b) {
        double val = 0;
        for( int k =0; k < 3; ++k )
            val += Math.pow((a[k] - b[k]), 2);
        return Math.sqrt(val);
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

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchDisplacementClient.class);

}
