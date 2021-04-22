package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;

/**
 * Spark client for rendering intensity adjusted montage scapes for a range of layers within a stack.
 *
 * @author Eric Trautman
 */
public class IntensityAdjustedScapeClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered layers (e.g. /nrs/flyem/render/scapes)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--format",
                description = "Format for rendered boxes"
        )
        public String format = Utils.PNG_FORMAT;

        File getSectionRootDirectory(final Date forRunTime) {

            final String scapeDir = "intensity_adjusted_scapes_" +
                                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(forRunTime);
            final Path sectionRootPath = Paths.get(rootDirectory,
                                                   renderWeb.owner,
                                                   renderWeb.project,
                                                   stack,
                                                   scapeDir).toAbsolutePath();
            return sectionRootPath.toFile();
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final IntensityAdjustedScapeClient client = new IntensityAdjustedScapeClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private IntensityAdjustedScapeClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("IntensityAdjustedScapeClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();

        LOG.info("run: appId is {}", sparkAppId);

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.layerRange.minZ,
                                                                      parameters.layerRange.maxZ);
        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final File sectionRootDirectory = parameters.getSectionRootDirectory(new Date());
        FileUtil.ensureWritableDirectory(sectionRootDirectory);

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);

        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
        int maxZCharacters = 5;
        for (long z = 100000; z < stackBounds.getMaxZ().longValue(); z = z * 10) {
            maxZCharacters++;
        }
        final String slicePathFormatSpec =
                sectionRootDirectory.getAbsolutePath() + "/z.%0" + maxZCharacters + "d." + parameters.format;

        final JavaRDD<Double> rddSectionData = sparkContext.parallelize(zValues);

        final Function<Double, Integer> generateScapeFunction =
                z -> {

                    final int integralZ = z.intValue();

                    LogUtilities.setupExecutorLog4j("z " + integralZ);

                    final RenderDataClient workerDataClient = parameters.renderWeb.getDataClient();

                    // NOTE: need to create interval here since it is not labelled as Serializable
                    final Interval interval = RenderTools.stackBounds(stackMetaData);

                    final RandomAccessibleInterval<UnsignedByteType> slice =
                            AdjustBlock.renderIntensityAdjustedSlice(parameters.stack,
                                                                     workerDataClient,
                                                                     interval,
                                                                     1.0,
                                                                     false,
                                                                     integralZ);
                    final BufferedImage sliceImage =
                            ImageJFunctions.wrap(slice, "").getProcessor().getBufferedImage();

                    final String slicePath = String.format(slicePathFormatSpec, integralZ);

                    Utils.saveImage(sliceImage, slicePath, parameters.format, false, 0.85f);

                    return 1;
                };

        final JavaRDD<Integer> rddLayerCounts = rddSectionData.map(generateScapeFunction);

        final long processedLayerCount = rddLayerCounts.collect().stream().reduce(0, Integer::sum);

        LOG.info("run: collected stats");
        LOG.info("run: generated images for {} layers", processedLayerCount);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityAdjustedScapeClient.class);
}
