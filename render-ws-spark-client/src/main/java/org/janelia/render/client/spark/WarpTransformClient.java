package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

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
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.janelia.render.client.parameter.WarpStackParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class WarpTransformClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @ParametersDelegate
        public WarpStackParameters warp = new WarpStackParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                required = false,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

        public Set<Double> getZValues() {
            return (zValues == null) ? Collections.emptySet() : new HashSet<>(zValues);
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.warp.initDefaultValues(parameters.renderWeb);

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


        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(parameters.warp.montageStack,
                                                                                       parameters.layerRange.minZ,
                                                                                       parameters.layerRange.maxZ,
                                                                                       parameters.getZValues());
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("montage stack does not contain any matching z values");
        }

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final RenderDataClient targetDataClient = parameters.warp.getTargetDataClient();

        final StackMetaData montageStackMetaData = sourceDataClient.getStackMetaData(parameters.warp.montageStack);
        targetDataClient.setupDerivedStack(montageStackMetaData, parameters.warp.targetStack);

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final Function<List<Double>, Long> warpFunction = (Function<List<Double>, Long>) zBatch -> {

            LOG.info("warpFunction: entry");

            long processedTileCount = 0;

            for (int i = 0; i < zBatch.size(); i++) {

                final Double z = zBatch.get(i);

                LogUtilities.setupExecutorLog4j("z " + z);

                LOG.info("warpFunction: processing layer {} of {}, remaining layer z values are {}",
                         i + 1, zBatch.size(), zBatch.subList(i+1, zBatch.size()));

                final TileSpecValidator tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();
                final RenderDataClient montageDataClient = parameters.renderWeb.getDataClient();
                final RenderDataClient alignDataClient = parameters.warp.getAlignDataClient();
                final RenderDataClient targetDataClient1 = parameters.warp.getTargetDataClient();

                final ResolvedTileSpecCollection montageTiles =
                        montageDataClient.getResolvedTiles(parameters.warp.montageStack, z);
                final ResolvedTileSpecCollection alignTiles =
                        alignDataClient.getResolvedTiles(parameters.warp.alignStack, z);

                final TransformSpec warpTransformSpec = buildTransform(montageTiles.getTileSpecs(),
                                                                       alignTiles.getTileSpecs(),
                                                                       z);

                LOG.info("warpFunction: derived warp transform for {}", z);

                montageTiles.addTransformSpecToCollection(warpTransformSpec);
                montageTiles.addReferenceTransformToAllTiles(warpTransformSpec.getId(), false);

                final int totalNumberOfTiles = montageTiles.getTileCount();
                if (tileSpecValidator != null) {
                    montageTiles.setTileSpecValidator(tileSpecValidator);
                    montageTiles.removeInvalidTileSpecs();
                }
                final int numberOfRemovedTiles = totalNumberOfTiles - montageTiles.getTileCount();

                LOG.info("warpFunction: added transform and derived bounding boxes for {} tiles, removed {} bad tiles",
                         totalNumberOfTiles, numberOfRemovedTiles);

                if (montageTiles.getTileCount() == 0) {
                    throw new IllegalStateException("no tiles left to save after filtering invalid tiles");
                }

                targetDataClient1.saveResolvedTiles(montageTiles, parameters.warp.targetStack, z);

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
