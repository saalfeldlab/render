package org.janelia.render.client.spark.n5;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.BoundsBatch;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorCacheSpec;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.CrossCorrelationWithNextRegionalDataN5Writer;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.zspacing.AreaCrossCorrelationWithNext;
import org.janelia.render.client.zspacing.CrossCorrelationData;
import org.janelia.render.client.zspacing.CrossCorrelationWithNextRegionalData;
import org.janelia.render.client.zspacing.HeadlessZPositionCorrection;
import org.janelia.render.client.zspacing.loader.RenderLayerLoader;
import org.janelia.render.client.zspacing.loader.ResinMaskParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for deriving and exporting a stack's cross correlation data as an N5 volume.
 *
 * @author Eric Trautman
 */
public class CorrelationN5Client {

    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--scale",
                description = "Scale to render each region",
                required = true)
        public Double scale;

        @Parameter(
                names = "--n5Path",
                description = "Root N5 path, e.g. /nrs/cellmap/data/jrc_ut23-0590-001/jrc_ut23-0590-001.n5",
                required = true)
        public String n5Path;

        @Parameter(
                names = "--regionRows",
                description = "Number of correlation region rows")
        public int regionRows = 8;

        @Parameter(
                names = "--regionColumns",
                description = "Number of correlation region columns")
        public int regionColumns = 8;

        @Parameter(
                names = "--regionCacheGB",
                description = "Number cache gigabytes to allocate for region rendering during correlation derivation")
        public int regionCacheGB = 10;

        @ParametersDelegate
        public ResinMaskParameters resin = new ResinMaskParameters();

        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--stackResolutionUnit",
                description = "Unit description for stack resolution values (e.g. nm, um, ...)")
        public String stackResolutionUnit = "nm";

        public Bounds getBoundsForRun(final Bounds defaultBounds) {
            final Bounds defaultedLayerBounds = layerBounds.overrideBounds(defaultBounds);
            final Bounds runBounds = layerRange.overrideBounds(defaultedLayerBounds);
            LOG.info("getBoundsForRun: returning {}", runBounds);
            return runBounds;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationN5Client.class);

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final CorrelationN5Client.Parameters parameters = new CorrelationN5Client.Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CorrelationN5Client client = new CorrelationN5Client(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public CorrelationN5Client(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {
        final SparkConf conf = new SparkConf().setAppName("CorrelationN5Client");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("runWithContext: entry, defaultParallelism={}", sparkContext.defaultParallelism());

        final String runTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        final String datasetName =
                "/cc/" + parameters.renderWeb.project + "/" + parameters.stack + "___" + runTime + "/s0";

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final RenderWebServiceUrls urls = sourceDataClient.getUrls();
        final String stackUrlString = urls.getStackUrlString(parameters.stack);

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        final Bounds defaultBounds = stackMetaData.getStats().getStackBounds();
        final Bounds boundsForRun = parameters.getBoundsForRun(defaultBounds);

        final List<BoundsBatch> batchList = BoundsBatch.batchByAdjacentZThenXY(boundsForRun,
                                                                               parameters.regionRows,
                                                                               parameters.regionColumns,
                                                                               sparkContext.defaultParallelism());

        final List<AreaCrossCorrelationWithNext> areaList = deriveAreaCrossCorrelations(sparkContext,
                                                                                        batchList,
                                                                                        stackUrlString);

        final List<CrossCorrelationWithNextRegionalData> layerList = assembleAreaCrossCorrelations(areaList);

        CrossCorrelationWithNextRegionalDataN5Writer.writeN5(layerList,
                                                             parameters.n5Path,
                                                             datasetName,
                                                             stackMetaData,
                                                             parameters.stackResolutionUnit);

        LOG.info("runWithContext: exit");
    }

    private List<AreaCrossCorrelationWithNext> deriveAreaCrossCorrelations(final JavaSparkContext sparkContext,
                                                                           final List<BoundsBatch> batchList,
                                                                           final String stackUrlString) {

        LOG.info("deriveAreaCrossCorrelations: entry, batchList.size={}", batchList.size());

        // redefine parameters needed remotely here so that Spark does not need to serialize the client
        final ResinMaskParameters resinMaskParameters = parameters.resin;
        final double renderScale = parameters.scale;

        // This "spec" is broadcast to Spark executors allowing each one to construct and share
        // an ImageProcessor cache across each task assigned to the executor.
        final long cacheKilobytes = parameters.regionCacheGB * 1_000_000L;
        final ImageProcessorCacheSpec cacheSpec =
                new ImageProcessorCacheSpec(cacheKilobytes,
                                            true,
                                            false);
        final Broadcast<ImageProcessorCacheSpec> broadcastCacheSpec = sparkContext.broadcast(cacheSpec);

        final JavaRDD<BoundsBatch> rddBatch = sparkContext.parallelize(batchList);
        final JavaRDD<List<AreaCrossCorrelationWithNext>> rddAreaCrossCorrelation = rddBatch.map(boundsBatch -> {

            final List<AreaCrossCorrelationWithNext> list = new ArrayList<>();
            final ImageProcessorCache ipCache = broadcastCacheSpec.getValue().getSharableInstance();
            for (final Bounds bounds : boundsBatch.getList()) {
                final String layerUrlPattern = String.format("%s/z/%s/box/%d,%d,%d,%d,%s/render-parameters",
                                                             stackUrlString, "%s",
                                                             bounds.getX(), bounds.getY(),
                                                             bounds.getWidth(), bounds.getHeight(),
                                                             renderScale);

                final List<Double> sortedZList = IntStream
                        .rangeClosed(bounds.getMinZ().intValue(), bounds.getMaxZ().intValue())
                        .boxed()
                        .map(Integer::doubleValue)
                        .collect(Collectors.toList());

                final RenderLayerLoader layerLoader = resinMaskParameters.buildLoader(layerUrlPattern,
                                                                                      sortedZList,
                                                                                      ipCache,
                                                                                      renderScale);
                final int firstZ = bounds.getMinZ().intValue();
                final int comparisonRange = 1;
                final CrossCorrelationData ccData =
                        HeadlessZPositionCorrection.deriveCrossCorrelationWithCachedLoaders(layerLoader,
                                                                                            comparisonRange,
                                                                                            firstZ);
                list.add(new AreaCrossCorrelationWithNext(bounds,
                                                          sortedZList,
                                                          ccData));
            }
            return list;
        });

        final List<AreaCrossCorrelationWithNext> results = rddAreaCrossCorrelation.collect()
                .stream().flatMap(List::stream).collect(Collectors.toList());

        LOG.info("deriveAreaCrossCorrelations: exit");

        return results;
    }

    private List<CrossCorrelationWithNextRegionalData> assembleAreaCrossCorrelations(final List<AreaCrossCorrelationWithNext> areaList) {
        LOG.info("assembleAreaCrossCorrelations: entry, assembling {} areas", areaList.size());

        final List<CrossCorrelationWithNextRegionalData> layerList = new ArrayList<>();

        final Map<Double, List<AreaCrossCorrelationWithNext>> zToAreaListMap = new HashMap<>();
        for (final AreaCrossCorrelationWithNext area : areaList) {
            final double[] zValues = area.getzValues();
            for (int i = 0; i < zValues.length - 1; i++) { // exclude last z since it won't have a correlation with next
                final double z = zValues[i];
                final List<AreaCrossCorrelationWithNext> areasForZ =
                        zToAreaListMap.computeIfAbsent(z, zValue -> new ArrayList<>());
                areasForZ.add(area);
            }
        }

        for (final Double z : zToAreaListMap.keySet().stream().sorted().collect(Collectors.toList())) {
            layerList.add(assembleLayerCrossCorrelations(z,
                                                         zToAreaListMap.get(z),
                                                         parameters.regionRows,
                                                         parameters.regionColumns));
        }

        LOG.info("assembleAreaCrossCorrelations: exit, returning data for {} z layers", layerList.size());

        return layerList;
    }

    public CrossCorrelationWithNextRegionalData assembleLayerCrossCorrelations(final Double pZ,
                                                                               final List<AreaCrossCorrelationWithNext> areaList,
                                                                               final int numberOfRegionRows,
                                                                               final int numberOfRegionColumns) {
        final CrossCorrelationWithNextRegionalData ccData =
                new CrossCorrelationWithNextRegionalData(pZ,
                                                         pZ + 1.0,
                                                         null,
                                                         null,
                                                         numberOfRegionRows,
                                                         numberOfRegionColumns);

        // building separate sorted list even though it is probably ok to simply sort areaList in place
        final List<AreaCrossCorrelationWithNext> sortedAreaList =
                areaList.stream().sorted(AreaCrossCorrelationWithNext.COMPARE_BY_MIN_YXZ).collect(Collectors.toList());

        // validate input data
        final int expectedCellCount = numberOfRegionRows * numberOfRegionColumns;
        if (areaList.size() != expectedCellCount) {
            final List<String> sortedAreaBoundsStrings =
                    sortedAreaList.stream().map(a -> a.getBounds().toString()).collect(Collectors.toList());
            LOG.warn("assembleLayerCrossCorrelations: pZ={}, rowCount={}, columnCount={}, areaCount={}, sortedAreaBoundsStrings={}",
                     pZ, numberOfRegionRows, numberOfRegionColumns, areaList.size(), sortedAreaBoundsStrings);
            throw new IllegalArgumentException("expected " + expectedCellCount + " cells but found " + areaList.size());
        }

        // assemble ...
        int areaIndex = 0;
        for (int row = 0; row < numberOfRegionRows; row++) {
            for (int column = 0; column < numberOfRegionColumns; column++) {
                final AreaCrossCorrelationWithNext area = sortedAreaList.get(areaIndex);
                ccData.setValue(row, column, area.getCorrelationWithNextForZ(pZ));
                areaIndex++;
            }
        }

        return ccData;
    }

}
