package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.FileUtil;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for rendering montage scapes for a range of layers within a stack.
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld
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
                names = "--maxImagesPerDirectory",
                description = "Maximum number of images to render in one directory",
                required = false)
        private Integer maxImagesPerDirectory = 1000;

        @Parameter(
                names = "--scale",
                description = "Scale for each rendered layer",
                required = false)
        private Double scale = 0.02;

        @Parameter(
                names = "--zScale",
                description = "Ratio of z to xy resolution for creating isotropic layer projections (omit to skip projection)",
                required = false)
        private Double zScale;

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
                names = "--channels",
                description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')",
                required = false)
        private String channels;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill image with noise before rendering to improve point match derivation",
                required = false)
        private boolean fillWithNoise = false;

        @Parameter(
                names = "--useLayerBounds",
                description = "Base each scape on layer bounds instead of on stack bounds (e.g. for unaligned data)",
                required = false,
                arity = 1)
        private boolean useLayerBounds = false;

        @Parameter(
                names = "--minX",
                description = "Left most pixel coordinate in world coordinates.  Default is minX of stack (or layer when --useLayerBounds true)",
                required = false)
        private Double minX;

        @Parameter(
                names = "--minY",
                description = "Top most pixel coordinate in world coordinates.  Default is minY of stack (or layer when --useLayerBounds true)",
                required = false)
        private Double minY;

        @Parameter(
                names = "--width",
                description = "Width in world coordinates.  Default is maxX - minX of stack (or layer when --useLayerBounds true)",
                required = false)
        private Double width;

        @Parameter(
                names = "--height",
                description = "Height in world coordinates.  Default is maxY - minY of stack (or layer when --useLayerBounds true)",
                required = false)
        private Double height;

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

        public File getSectionRootDirectory() {

            final String scapeDir = "scape_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            final Path sectionRootPath = Paths.get(rootDirectory,
                                                   project,
                                                   stack,
                                                   scapeDir).toAbsolutePath();
            return sectionRootPath.toFile();
        }

        public double getEffectiveBound(final Double layerValue,
                                        final Double stackValue,
                                        final Double parameterValue) {
            final double value;
            if (parameterValue == null) {
                if (useLayerBounds) {
                    value = layerValue;
                } else {
                    value = stackValue;
                }
            } else {
                value = parameterValue;
            }
            return value;
        }

        public Double getMaxX(final double effectiveMinX) {
            return (width == null) ? null : effectiveMinX + width;
        }

        public Double getMaxY(final double effectiveMinY) {
            return (height == null) ? null : effectiveMinY + height;
        }

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

        final SparkConf conf = new SparkConf().setAppName("ScapeClient");
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

        // projection process depends upon z ordering, so sort section data results by z ...
        Collections.sort(sectionDataList, SectionData.Z_COMPARATOR);

        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final File sectionRootDirectory = parameters.getSectionRootDirectory();
        FileUtil.ensureWritableDirectory(sectionRootDirectory);

        // save run parameters so that we can understand render context later if necessary
        final File parametersFile = new File(sectionRootDirectory, "scape_parameters.json");
        JsonUtils.MAPPER.writeValue(parametersFile, parameters);

        final List<RenderSection> renderSectionList =
                getRenderSections(sourceDataClient, sectionDataList, sectionRootDirectory);

        final JavaRDD<RenderSection> rddSectionData = sparkContext.parallelize(renderSectionList);

        final Function<RenderSection, Integer> generateScapeFunction = new Function<RenderSection, Integer>() {

            final
            @Override
            public Integer call(final RenderSection renderSection)
                    throws Exception {

                final Double z = renderSection.getFirstZ();
                LogUtilities.setupExecutorLog4j("z " + z);

                final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);

                // set cache size to 50MB so that masks get cached but most of RAM is left for target image
                final int maxCachedPixels = 50 * 1000000;
                final ImageProcessorCache imageProcessorCache =
                        new ImageProcessorCache(maxCachedPixels, false, false);

                final boolean isProjectionNeeded = renderSection.isProjectionNeeded();
                BufferedImage sectionImage = null;
                ImageStack projectedStack = null;

                for (final SectionData sectionData : renderSection.getSectionDataList()) {

                    final String parametersUrl =
                            sourceDataClient.getRenderParametersUrlString(parameters.stack,
                                                                          sectionData.getMinX(),
                                                                          sectionData.getMinY(),
                                                                          sectionData.getZ(),
                                                                          sectionData.getWidth(),
                                                                          sectionData.getHeight(),
                                                                          parameters.scale);

                    LOG.debug("generateScapeFunction: loading {}", parametersUrl);

                    final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
                    renderParameters.setDoFilter(parameters.doFilter);
                    renderParameters.setChannels(parameters.channels);

                    sectionImage = renderParameters.openTargetImage();

                    if (isProjectionNeeded && (projectedStack == null)) {
                        projectedStack = new ImageStack(sectionImage.getWidth(), sectionImage.getHeight());
                    }

                    if (parameters.fillWithNoise) {
                        final ByteProcessor ip = new ByteProcessor(sectionImage.getWidth(), sectionImage.getHeight());
                        mpicbg.ij.util.Util.fillWithNoise(ip);
                        sectionImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
                    }

                    ArgbRenderer.render(renderParameters, sectionImage, imageProcessorCache);

                    if (isProjectionNeeded) {
                        projectedStack.addSlice(new ColorProcessor(sectionImage).convertToByteProcessor());
                    }
                }

                if (projectedStack != null) {

                    LOG.debug("projecting {} sections", projectedStack.getSize());

                    final ZProjector projector = new ZProjector(new ImagePlus("", projectedStack));
                    projector.setMethod(ZProjector.AVG_METHOD);
                    projector.doProjection();
                    final ImageProcessor ip = projector.getProjection().getProcessor();
                    sectionImage = ip.getBufferedImage();
                }

                final File sectionFile = renderSection.getOutputFile(parameters.format);

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

    private List<RenderSection> getRenderSections(final RenderDataClient sourceDataClient,
                                                  final List<SectionData> sectionDataList,
                                                  final File sectionRootDirectory)
            throws IOException {

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        int maxZCharacters = 3; // %3.1d => 1.0
        for (long z = 1; z < stackBounds.getMaxZ().longValue(); z = z * 10) {
            maxZCharacters++;
        }
        final String zFormatSpec = "%0" + maxZCharacters + ".1f";

        final double zScale = parameters.zScale == null ? 0.0 : parameters.zScale / parameters.scale;

        final List<RenderSection> renderSectionList = new ArrayList<>(sectionDataList.size());

        RenderSection currentRenderSection = null;
        double currentZ = -1;

        for (final SectionData sectionData : sectionDataList) {

            if ((currentRenderSection == null) || (sectionData.getZ() - zScale >= currentZ)) {
                currentZ = sectionData.getZ();
                currentRenderSection = new RenderSection(currentZ,
                                                         renderSectionList.size(),
                                                         zFormatSpec,
                                                         parameters.maxImagesPerDirectory,
                                                         sectionRootDirectory);
                renderSectionList.add(currentRenderSection);
            }

            final double minX = parameters.getEffectiveBound(sectionData.getMinX(), stackBounds.getMinX(), parameters.minX);
            final double minY = parameters.getEffectiveBound(sectionData.getMinY(), stackBounds.getMinY(), parameters.minY);

            final SectionData boundedSectionData =
                    new SectionData(sectionData.getSectionId(),
                                    sectionData.getZ(),
                                    sectionData.getTileCount(),
                                    minX,
                                    parameters.getEffectiveBound(sectionData.getMaxX(),
                                                                 stackBounds.getMaxX(),
                                                                 parameters.getMaxX(minX)),
                                    minY,
                                    parameters.getEffectiveBound(sectionData.getMaxY(),
                                                                 stackBounds.getMaxY(),
                                                                 parameters.getMaxY(minY)));

            final long scaledSectionWidth = (long) (boundedSectionData.getWidth() * parameters.scale + 0.5);
            final long scaledSectionHeight = (long) (boundedSectionData.getHeight() * parameters.scale + 0.5);
            final long sectionPixelCount = scaledSectionWidth * scaledSectionHeight;

            if (sectionPixelCount >= Integer.MAX_VALUE) {
                final DecimalFormat formatter = new DecimalFormat("#,###");
                throw new IllegalArgumentException("section " + boundedSectionData + " has " +
                                                   formatter.format(sectionPixelCount) + " pixels at scale " +
                                                   parameters.scale + " which is greater than the maximum allowed " +
                                                   formatter.format(Integer.MAX_VALUE));
            }

            currentRenderSection.addSection(boundedSectionData);
        }

        return renderSectionList;
    }

    public static class RenderSection implements Serializable {

        private final Double firstZ;
        private final List<SectionData> sectionDataList;
        private final File outputDir;
        private final String zFormatSpec;

        public RenderSection(final Double firstZ,
                             final int stackIndex,
                             final String zFormatSpec,
                             final int maxImagesPerDirectory,
                             final File sectionRootDirectory) {

            this.firstZ = firstZ;
            this.sectionDataList = new ArrayList<>();
            this.zFormatSpec = zFormatSpec;

            final int imageDirectoryIndex = stackIndex / maxImagesPerDirectory;
            final String imageDirectoryName = String.format("%03d", imageDirectoryIndex);
            this.outputDir = new File(sectionRootDirectory, imageDirectoryName);
        }

        public List<SectionData> getSectionDataList() {
            return sectionDataList;
        }

        public Double getFirstZ() {
            return firstZ;
        }

        public boolean isProjectionNeeded() {
            return sectionDataList.size() > 1;
        }

        public File getOutputFile(final String fileExtension) {
            final String paddedZName;
            if (sectionDataList.size() > 1) {
                final Double lastZ = sectionDataList.get(sectionDataList.size() - 1).getZ();
                final String formatPattern = "z" + zFormatSpec + "_to_" + zFormatSpec;
                paddedZName = String.format(formatPattern, firstZ, lastZ);
            } else {
                final String formatPattern = "z" + zFormatSpec;
                paddedZName = String.format(formatPattern, firstZ);
            }
            return new File(outputDir, paddedZName + "." + fileExtension.toLowerCase());
        }

        public void addSection(final SectionData sectionData) {
            this.sectionDataList.add(sectionData);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScapeClient.class);
}
