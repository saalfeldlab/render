package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.trakem2.transform.CoordinateTransform;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.warp.AbstractWarpTransformBuilder;
import org.janelia.alignment.warp.ThinPlateSplineBuilder;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParametersWithValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class WarpTransformClient
        implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(
                names = "--stack",
                description = "Montage stack name",
                required = true)
        private String montageStack;

        @Parameter(
                names = "--alignOwner",
                description = "Name of align stack owner (default is same as montage stack owner)",
                required = false)
        private String alignOwner;

        @Parameter(
                names = "--alignProject",
                description = "Name of align stack project (default is same as montage stack project)",
                required = false)
        private String alignProject;

        @Parameter(
                names = "--alignStack",
                description = "Align stack name",
                required = true)
        private String alignStack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as montage stack owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as montage stack project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Target stack name",
                required = true)
        private String targetStack;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be processed",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be processed",
                required = false)
        private Double maxZ;

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                required = false,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        private List<Double> zValues;

        public Set<Double> getZValues() {
            return (zValues == null) ? Collections.emptySet() : new HashSet<Double>(zValues);
        }

        public String getAlignOwner() {
            return alignOwner == null ? owner : alignOwner;
        }

        public String getAlignProject() {
            return alignProject == null ? project : alignProject;
        }

        public String getTargetOwner() {
            return targetOwner == null ? owner : targetOwner;
        }

        public String getTargetProject() {
            return targetProject == null ? project : targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, WarpTransformClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final WarpTransformClient client = new WarpTransformClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public WarpTransformClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("WarpTransformClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(parameters.montageStack,
                                                                                       parameters.minZ,
                                                                                       parameters.maxZ,
                                                                                       parameters.getZValues());
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("montage stack does not contain any matching z values");
        }

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData montageStackMetaData = sourceDataClient.getStackMetaData(parameters.montageStack);
        targetDataClient.setupDerivedStack(montageStackMetaData, parameters.targetStack);

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final Function<List<Double>, Long> warpFunction = (Function<List<Double>, Long>) zBatch -> {

            LOG.info("warpFunction: entry");

            long processedTileCount = 0;

            for (int i = 0; i < zBatch.size(); i++) {

                final Double z = zBatch.get(i);

                LogUtilities.setupExecutorLog4j("z " + z);

                LOG.info("warpFunction: processing layer {} of {}, remaining layer z values are {}",
                         i + 1, zBatch.size(), zBatch.subList(i+1, zBatch.size()));

                final TileSpecValidator tileSpecValidator = parameters.getValidatorInstance();

                final RenderDataClient montageDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                                parameters.owner,
                                                                                parameters.project);

                final RenderDataClient alignDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                              parameters.getAlignOwner(),
                                                                              parameters.getAlignProject());

                final RenderDataClient targetDataClient1 = new RenderDataClient(parameters.baseDataUrl,
                                                                                parameters.getTargetOwner(),
                                                                                parameters.getTargetProject());

                final ResolvedTileSpecCollection montageTiles =
                        montageDataClient.getResolvedTiles(parameters.montageStack, z);
                final ResolvedTileSpecCollection alignTiles =
                        alignDataClient.getResolvedTiles(parameters.alignStack, z);

                final TransformSpec warpTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                                       alignTiles.getTileSpecs(),
                                                                       z);

                LOG.info("warpFunction: derived warp transform for {}", z);

                montageTiles.addTransformSpecToCollection(warpTransformSpec);
                montageTiles.addReferenceTransformToAllTiles(warpTransformSpec.getId(), false);

                final int totalNumberOfTiles = montageTiles.getTileCount();
                if (tileSpecValidator != null) {
                    montageTiles.setTileSpecValidator(tileSpecValidator);
                    montageTiles.filterInvalidSpecs();
                }
                final int numberOfRemovedTiles = totalNumberOfTiles - montageTiles.getTileCount();

                LOG.info("warpFunction: added transform and derived bounding boxes for {} tiles, removed {} bad tiles",
                         totalNumberOfTiles, numberOfRemovedTiles);

                if (montageTiles.getTileCount() == 0) {
                    throw new IllegalStateException("no tiles left to save after filtering invalid tiles");
                }

                targetDataClient1.saveResolvedTiles(montageTiles, parameters.targetStack, z);

                processedTileCount += montageTiles.getTileCount();
            }

            LOG.info("warpFunction: exit");

            return processedTileCount;
        };

        final JavaRDD<Long> rddTileCounts = rddZValues.map(warpFunction);

        final List<Long> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Long tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: copied {} tiles", total);

        sparkContext.stop();
    }

    private static TransformSpec buildTransform(final Collection<TileSpec> montageTiles,
                                                final Collection<TileSpec> alignTiles,
                                                final Double z)
            throws Exception {

        final String warpType = "TPS";

        LOG.info("buildTransform: deriving {} transform", warpType);

        final AbstractWarpTransformBuilder< ? extends CoordinateTransform > transformBuilder =
                new ThinPlateSplineBuilder(montageTiles, alignTiles);
        final String transformId = z + "_" + warpType;
        final CoordinateTransform transform;

        transform = transformBuilder.call();

        LOG.info("buildTransform: completed {} transform derivation", warpType);

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpTransformClient.class);
}
