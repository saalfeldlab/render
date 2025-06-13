package org.janelia.render.client.spark.intensityadjust;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.AffineIntensityFilter;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Collection;
import java.util.stream.Collectors;

import ij.process.ImageProcessor;

/**
 * Spark client for layer-wise intensity adjustment for 16-bit images.
 * For each z-layer, the client:
 * 1. Loads all full-scale images from that layer
 * 2. Computes mean and standard deviation from all the image data
 * 3. Creates a filter spec that represents a linear mapping of mean +/- f*stdev into [min, max]
 * 4. Adds the filter spec to all tile specs and uploads them to a new stack
 * This is a 16bit version of the processing done for 8bit images in dat_to_scheffer_8_bit.py
 */
public class LayerNormalization16bitClient implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public RenderWebServiceParameters webservice = new RenderWebServiceParameters();

        @Parameter(names = "--stack",
                description = "Name of the source stack in the render project",
                required = true)
        public String stack;

        @Parameter(names = "--targetStack",
                description = "Name of the target stack in the render project",
                required = true)
        public String targetStack;

        @Parameter(names = "--minIntensity",
                description = "Minimum intensity value for the linear mapping (default is 30000)")
        public int minIntensity = 30000;

        @Parameter(names = "--maxIntensity",
                description = "Maximum intensity value for the linear mapping (default is 37000)")
        public int maxIntensity = 37000;

        @Parameter(names = "--saturationThreshold",
                description = "Number of intensity values to exclude from top and bottom of range (0-65535) when calculating statistics (default is 3)")
        public int saturationThreshold = 3;

        @Parameter(names = "--standardDeviationFactor",
                description = "Factor for standard deviation range (default is 4.0)")
        public double standardDeviationFactor = 4.0;
    }

    private static final Logger LOG = LoggerFactory.getLogger(LayerNormalization16bitClient.class);

    private final Parameters parameters;

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final LayerNormalization16bitClient client = new LayerNormalization16bitClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    public LayerNormalization16bitClient(final Parameters parameters) {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {
        final SparkConf conf = new SparkConf().setAppName("LayerNormalization16bitClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext) throws IOException {
        LOG.info("runWithContext: entry");

        final RenderDataClient renderClient = parameters.webservice.getDataClient();
        setUpTargetStack(renderClient);

        final List<Double> zValues = renderClient.getStackZValues(parameters.stack);

        // parallelize computation over z-layers
        final Broadcast<Parameters> parametersBroadcast = sparkContext.broadcast(parameters);

        sparkContext.parallelize(zValues).foreach(z -> processLayer(z, parametersBroadcast.getValue()));

        completeTargetStack(renderClient);
        LOG.info("runWithContext: exit");
    }

    private void setUpTargetStack(final RenderDataClient dataClient) throws IOException {
        final StackMetaData sourceStackMetaData = dataClient.getStackMetaData(parameters.stack);
        dataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
        LOG.info("setUpTargetStack: setup stack {}", parameters.targetStack);
    }

    private void completeTargetStack(final RenderDataClient dataClient) throws IOException {
        dataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        LOG.info("completeTargetStack: completed stack {}", parameters.targetStack);
    }

    private void processLayer(final Double z, final Parameters parameters) throws IOException {
        // enable logging on executors and add z-layer to log messages
        LogUtilities.setupExecutorLog4j("z=" + z.intValue());

        final RenderDataClient renderClient = parameters.webservice.getDataClient();
        final ResolvedTileSpecCollection rtsc = renderClient.getResolvedTiles(parameters.stack, z);
        final Collection<TileSpec> tileSpecs = rtsc.getTileSpecs();

        LOG.info("Processing layer z={} with {} tile specs", z, tileSpecs.size());

		// Load all images and calculate mean and standard deviation
        final ImageProcessorCache imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;
        double mean = 0.0;
        double variance = 0.0;
        int count = 0;

        // Apply saturation threshold - exclude pixels with values in the lowest or highest range
        final int lowerSaturationBound = parameters.saturationThreshold;
        final int upperSaturationBound = 65535 - parameters.saturationThreshold;

        for (final TileSpec tileSpec : tileSpecs) {
            final ImageProcessor ip = loadImage(tileSpec, imageProcessorCache);

            // Calculate mean and variance with Welford's algorithm
            // Skip pixels that are potentially saturated (using threshold)
            for (int y = 0; y < ip.getHeight(); y++) {
                for (int x = 0; x < ip.getWidth(); x++) {
                    // Skip pixels below lower bound or above upper bound
					final float intensity = ip.getf(x, y);
                    if (intensity < lowerSaturationBound || intensity > upperSaturationBound) {
                        continue;
                    }

                    final double delta = intensity - mean;
                    ++count;
                    mean += delta / count;
                    variance += delta * (intensity - mean);
                }
            }
        }
        final double stdDev = Math.sqrt(variance / count);

        LOG.info("Layer z={} stats: mean={}, stdDev={}, unsaturatedPixels={}", z, mean, stdDev, count);

        // Calculate the linear mapping parameters
        final double lowerBound = mean - parameters.standardDeviationFactor * stdDev;
        final double upperBound = mean + parameters.standardDeviationFactor * stdDev;
        final double a = (parameters.maxIntensity - parameters.minIntensity) / (upperBound - lowerBound);
        final double b = parameters.minIntensity - (a * lowerBound);

        LOG.info("Layer z={} linear mapping: a={}, b={}", z, a, b);
        LOG.info("This maps intensity range [{}, {}] to [{}, {}]",
                lowerBound, upperBound, parameters.minIntensity, parameters.maxIntensity);

        // Add intensity filter to each tile spec
        for (final TileSpec tileSpec : tileSpecs) {
            final AffineIntensityFilter filter = new AffineIntensityFilter(a, b);
            tileSpec.addFilterSpec(FilterSpec.forFilter(filter));
        }

        // Save the modified tile specs to the target stack
        renderClient.saveResolvedTiles(rtsc, parameters.targetStack, z);
        LOG.info("Saved {} tile specs for layer z={} to stack {}", tileSpecs.size(), z, parameters.targetStack);
    }

    private ImageProcessor loadImage(final TileSpec tileSpec, final ImageProcessorCache imageProcessorCache) {
        final ChannelSpec firstChannelSpec = tileSpec.getAllChannels().get(0);
        final String tileId = tileSpec.getTileId();
        final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapImageAndMask(tileId);

        return imageProcessorCache.get(imageAndMask.getImageUrl(),
                                      0,
                                      false,
                                      firstChannelSpec.is16Bit(),
                                      imageAndMask.getImageLoaderType(),
                                      imageAndMask.getImageSliceNumber());
    }
}
