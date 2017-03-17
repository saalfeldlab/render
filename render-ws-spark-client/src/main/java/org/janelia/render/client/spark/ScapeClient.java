package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import ij.process.ByteProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for rendering montage scapes for a range of layers within a stack.
 *
 * @author Eric Trautman
 */
public class ScapeClient
        implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        private String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered layers (e.g. /groups/flyTEM/flyTEM/rendered_scapes)",
                required = true)
        private String rootDirectory;

        @Parameter(
                names = "--scale",
                description = "Scale for each rendered layer",
                required = false)
        private Double scale = 0.02;

        @Parameter(
                names = "--format",
                description = "Format for rendered boxes",
                required = false)
        private String format = Utils.JPEG_FORMAT;

        @Parameter(
                names = "--doFilter",
                description = "Use ad hoc filter to support alignment",
                required = false)
        private boolean doFilter = false;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill image with noise before rendering to improve point match derivation",
                required = false)
        private boolean fillWithNoise = false;

        @Parameter(
                names = "--useStackBounds",
                description = "Base each scape on stack bounds instead of on section bounds (e.g. for aligned data)",
                required = false)
        private boolean useStackBounds = false;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be rendered",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be rendered",
                required = false)
        private Double maxZ;

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, ScapeClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ScapeClient client = new ScapeClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public ScapeClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("BoxClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(parameters.stack,
                                                                                       parameters.minZ,
                                                                                       parameters.maxZ);

        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final List<SectionData> adjustedSectionDataList;
        if (parameters.useStackBounds) {
            final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
            final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

            // TODO: validate size * scale is not too big

            adjustedSectionDataList = new ArrayList<>(sectionDataList.size());
            for (final SectionData sectionData : sectionDataList) {
                adjustedSectionDataList.add(new SectionData(sectionData.getSectionId(),
                                                            sectionData.getZ(),
                                                            sectionData.getTileCount(),
                                                            stackBounds.getMinX(),
                                                            stackBounds.getMaxX(),
                                                            stackBounds.getMinY(),
                                                            stackBounds.getMaxY()));
            }

        } else {
            adjustedSectionDataList = sectionDataList;
        }

        final Path projectPath = Paths.get(parameters.rootDirectory,
                                           parameters.project).toAbsolutePath();

        String runContext = "scale_" + parameters.scale;
        if (parameters.doFilter) {
            runContext = runContext + "_filter";
        }
        if (parameters.fillWithNoise) {
            runContext = runContext + "_fill";
        }
        if (parameters.useStackBounds) {
            runContext = runContext + "_align";
        }

        final Path sectionBasePath = Paths.get(projectPath.toString(),
                                               parameters.stack,
                                               runContext).toAbsolutePath();

        final File sectionBaseDirectory = sectionBasePath.toFile();
        for (final SectionData sectionData : adjustedSectionDataList) {
            final File sectionFile = getSectionFile(sectionBaseDirectory,
                                                    sectionData.getZ(),
                                                    parameters.format.toLowerCase());
            ensureWritableDirectory(sectionFile.getParentFile());
        }


        final JavaRDD<SectionData> rddSectionData = sparkContext.parallelize(adjustedSectionDataList);

        final Function<SectionData, Integer> generateScapeFunction = new Function<SectionData, Integer>() {

            final
            @Override
            public Integer call(final SectionData sectionData)
                    throws Exception {

                final Double z = sectionData.getZ();
                LogUtilities.setupExecutorLog4j("z " + z);

                final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);

                final int width = (int) (sectionData.getMaxX() - sectionData.getMinX() + 0.5);
                final int height = (int) (sectionData.getMaxY() - sectionData.getMinY() + 0.5);

                final String parametersUrl =
                        sourceDataClient.getRenderParametersUrlString(parameters.stack,
                                                                      sectionData.getMinX(),
                                                                      sectionData.getMinY(),
                                                                      z,
                                                                      width,
                                                                      height,
                                                                      parameters.scale);

                LOG.debug("generateScapeFunction: loading {}", parametersUrl);

                final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
                renderParameters.setDoFilter(parameters.doFilter);

                final File sectionFile = getSectionFile(sectionBaseDirectory,
                                                        sectionData.getZ(),
                                                        parameters.format.toLowerCase());

                final BufferedImage sectionImage = renderParameters.openTargetImage();

                if (parameters.fillWithNoise) {
                    final ByteProcessor ip = new ByteProcessor(sectionImage.getWidth(), sectionImage.getHeight());
                    mpicbg.ij.util.Util.fillWithNoise(ip);
                    sectionImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
                }

                // set cache size to 50MB so that masks get cached but most of RAM is left for target image
                final int maxCachedPixels = 50 * 1000000;
                final ImageProcessorCache imageProcessorCache =
                        new ImageProcessorCache(maxCachedPixels, false, false);

                Render.render(renderParameters, sectionImage, imageProcessorCache);

                Utils.saveImage(sectionImage, sectionFile.getAbsolutePath(), parameters.format, true, 0.85f);

                return 1;
            }
        };

        final JavaRDD<Integer> rddLayerCounts = rddSectionData.map(generateScapeFunction);

        final List<Integer> layerCountList = rddLayerCounts.collect();
        long total = 0;
        for (final Integer layerCount : layerCountList) {
            total += layerCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: generated boxes for {} layers", total);

        sparkContext.stop();
    }

    public static File getSectionFile(final File sectionBaseDirectory,
                                      final Double z,
                                      final String format) {

        final String paddedZ = String.format("%08.1f", z);
        final File thousandsDir = new File(sectionBaseDirectory, paddedZ.substring(0, 3));
        return new File(thousandsDir, paddedZ.substring(3) + "." + format);
    }

    public static void ensureWritableDirectory(final File directory) {
        // try twice to work around concurrent access issues
        if (! directory.exists()) {
            if (! directory.mkdirs()) {
                if (! directory.exists()) {
                    // last try
                    if (! directory.mkdirs()) {
                        if (! directory.exists()) {
                            throw new IllegalArgumentException("failed to create " + directory);
                        }
                    }
                }
            }
        }
        if (! directory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to " + directory);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScapeClient.class);
}
