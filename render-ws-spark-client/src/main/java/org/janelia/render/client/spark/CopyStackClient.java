package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.trakem2.transform.AffineModel2D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class CopyStackClient implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        private String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        private String targetStack;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be copied",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be copied",
                required = false)
        private Double maxZ;

        @Parameter(
                names = "--moveToOrigin",
                description = "If necessary, translate copied stack so that it's minX and minY are near the origin (default is to copy exact location)",
                required = false,
                arity = 0)
        private boolean moveToOrigin = false;

        @Parameter(
                names = "--excludeTileIdsMissingFromStacks",
                description = "Name(s) of stack(s) that contain ids of tiles to be included in target stack (assumes owner and project are same as source stack).",
                variableArity = true,
                required = false)
        private List<String> excludeTileIdsMissingFromStacks;

        public String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, CopyStackClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyStackClient client = new CopyStackClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public CopyStackClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("CopyStackClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

        final LeafTransformSpec moveStackTransform;
        if (parameters.moveToOrigin) {
            final StackStats sourceStackStats = sourceStackMetaData.getStats();
            final Bounds sourceStackBounds = sourceStackStats.getStackBounds();

            final double padding = 10.0;
            if ((sourceStackBounds.getMinX() < 0) || (sourceStackBounds.getMinX() > padding) ||
                (sourceStackBounds.getMinY() < 0) || (sourceStackBounds.getMinY() > padding)) {

                final double xOffset = padding - sourceStackBounds.getMinX();
                final double yOffset = padding - sourceStackBounds.getMinY();
                final String dataString = "1 0 0 1 " + xOffset + " " + yOffset;

                final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_hhmmss_SSS");
                moveStackTransform = new LeafTransformSpec("MOVE_STACK_" + sdf.format(new Date()),
                                                           null,
                                                           AffineModel2D.class.getName(),
                                                           dataString);
            } else {
                LOG.info("skipping move to origin since source stack is already near the origin");
                moveStackTransform = null;
            }

        } else {
            moveStackTransform = null;
        }

        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> copyFunction = new Function<Double, Integer>() {

            final
            @Override
            public Integer call(final Double z)
                    throws Exception {

                LogUtilities.setupExecutorLog4j("z " + z);

                final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);

                final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.getTargetOwner(),
                                                                               parameters.getTargetProject());

                final ResolvedTileSpecCollection sourceCollection =
                        sourceDataClient.getResolvedTiles(parameters.stack, z);

                final Set<String> tileIdsToKeep = new HashSet<>();
                String filterStack = null;
                if (parameters.excludeTileIdsMissingFromStacks != null) {

                    for (final String tileIdStack : parameters.excludeTileIdsMissingFromStacks) {

                        for (final TileBounds tileBounds : sourceDataClient.getTileBounds(tileIdStack, z)) {
                            tileIdsToKeep.add(tileBounds.getTileId());
                        }

                        // once a stack with tiles for the current z is found, use that as the filter
                        if (tileIdsToKeep.size() > 0) {
                            filterStack = tileIdStack;
                            break;
                        }
                    }

                }

                if (tileIdsToKeep.size() > 0) {
                    final int numberOfTilesBeforeFilter = sourceCollection.getTileCount();
                    sourceCollection.filterSpecs(tileIdsToKeep);
                    final int numberOfTilesRemoved = numberOfTilesBeforeFilter - sourceCollection.getTileCount();
                    LOG.info("removed {} tiles not found in {}", numberOfTilesRemoved, filterStack);
                }

                if (moveStackTransform != null) {
                    sourceCollection.addTransformSpecToCollection(moveStackTransform);
                    sourceCollection.addReferenceTransformToAllTiles(moveStackTransform.getId(), false);
                }

                sourceCollection.removeUnreferencedTransforms();

                targetDataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);

                return sourceCollection.getTileCount();
            }
        };

        final JavaRDD<Integer> rddTileCounts = rddZValues.map(copyFunction);

        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: copied {} tiles", total);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
