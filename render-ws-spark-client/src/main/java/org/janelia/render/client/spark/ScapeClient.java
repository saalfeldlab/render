package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ZProjector;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
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
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ScapeParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;

/**
 * Spark client for rendering montage scapes for a range of layers within a stack.
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld
 */
public class ScapeClient
        implements Serializable, AlignmentPipelineStep {

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

        @ParametersDelegate
        public ScapeParameters scape = new ScapeParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ScapeClient client = new ScapeClient();
                client.createContextAndRun(parameters, null);
            }
        };
        clientRunner.run();
    }

    public ScapeClient() {
    }

    /**
     * Create a spark context and run the client with the specified parameters.
     *
     * @param  scapeClientParameters         run parameters.
     * @param  numberOfLocalConcurrentTasks  specify for local runs, leave as null for cluster runs.
     *
     * @throws IOException
     *   if any errors occur while running the client.
     */
    public void createContextAndRun(final Parameters scapeClientParameters,
                                    final Integer numberOfLocalConcurrentTasks) throws IOException {

        SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());

        if (numberOfLocalConcurrentTasks != null) {
            conf = conf.setMaster("local[" + numberOfLocalConcurrentTasks + "]")
                    .set("spark.driver.bindAddress", "127.0.0.1")
                    .set("spark.driver.host",        "127.0.0.1");
        }

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            exportStackToFilesystem(sparkContext,
                                    scapeClientParameters.renderWeb.baseDataUrl,
                                    new StackId(scapeClientParameters.renderWeb.owner,
                                                scapeClientParameters.renderWeb.project,
                                                scapeClientParameters.stack),
                                    scapeClientParameters.layerRange,
                                    scapeClientParameters.scape);
        }
    }

    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("scape",
                                                                  pipelineParameters.getScape());
    }

    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final StackIdNamingGroup rawNamingGroup = pipelineParameters.getRawNamingGroup();
        final MultiProjectParameters multiProjectParameters = pipelineParameters.getMultiProject(rawNamingGroup);
        final RenderDataClient sourceDataClient = multiProjectParameters.getDataClient();
        final List<StackId> stackIdList = multiProjectParameters.stackIdWithZ.getStackIdList(sourceDataClient);

        for (final StackId stackId : stackIdList) {
            exportStackToFilesystem(sparkContext,
                                    multiProjectParameters.baseDataUrl,
                                    stackId,
                                    new ZRangeParameters(),
                                    pipelineParameters.getScape());
        }
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.RENDER_SCAPE_IMAGES;
    }

    public static void exportStackToFilesystem(final JavaSparkContext sparkContext,
                                               final String baseDataUrl,
                                               final StackId stackId,
                                               final ZRangeParameters layerRange,
                                               final ScapeParameters scape)
            throws IOException {

        LOG.info("exportStackToFilesystem: entry, stackId={}", stackId);

        final String stack = stackId.getStack();

        final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                       stackId.getOwner(),
                                                                       stackId.getProject());

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(stack);
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        final List<SectionData> sectionDataList = sourceDataClient.getStackSectionData(stack,
                                                                                       layerRange.minZ,
                                                                                       layerRange.maxZ);

        // projection process depends upon z ordering, so sort section data results by z ...
        sectionDataList.sort(SectionData.Z_COMPARATOR);

        if (sectionDataList.isEmpty()) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final File sectionRootDirectory = scape.getSectionRootDirectory(stackId.getProject(), stack);
        FileUtil.ensureWritableDirectory(sectionRootDirectory);

        // save run parameters so that we can understand render context later if necessary
        final File parametersFile = new File(sectionRootDirectory, "scape_parameters.json");
        JsonUtils.MAPPER.writeValue(parametersFile, scape);

        final double renderScale = scape.getScapeRenderScale(stackBounds,
                                                             sectionDataList);

        LOG.info("exportStackToFilesystem: set renderScale to {}", renderScale);

        final List<RenderSection> renderSectionList =
                getRenderSections(sectionDataList,
                                  sectionRootDirectory,
                                  stackBounds,
                                  scape,
                                  renderScale);

        final JavaRDD<RenderSection> rddSectionData = sparkContext.parallelize(renderSectionList);

        final boolean isTiffWithResolutionOutput = scape.resolutionUnit != null &&
                                                   (Utils.TIFF_FORMAT.equals(scape.format) ||
                                                    Utils.TIF_FORMAT.equals(scape.format));
        final List<Double> stackResolutionValues;
        if (isTiffWithResolutionOutput) {
            stackResolutionValues = stackMetaData.getCurrentResolutionValues();
        } else {
            stackResolutionValues = null;
        }

        final Function<RenderSection, Integer> generateScapeFunction =
                buildSectionScapeGenerationFunction(baseDataUrl,
                                                    stackId,
                                                    scape,
                                                    renderScale,
                                                    isTiffWithResolutionOutput,
                                                    stackResolutionValues);

        final JavaRDD<Integer> rddLayerCounts = rddSectionData.map(generateScapeFunction);

        final List<Integer> layerCountList = rddLayerCounts.collect();
        long total = 0;
        for (final Integer layerCount : layerCountList) {
            total += layerCount;
        }

        LOG.info("exportStackToFilesystem: collected stats");
        LOG.info("exportStackToFilesystem: generated boxes for {} layers", total);
    }

    private static List<RenderSection> getRenderSections(final List<SectionData> sectionDataList,
                                                         final File sectionRootDirectory,
                                                         final Bounds stackBounds,
                                                         final ScapeParameters scapeParameters,
                                                         final double renderScale) {

        int maxZCharacters = 3; // %3.1d => 1.0
        for (long z = 1; z < stackBounds.getMaxZ().longValue(); z = z * 10) {
            maxZCharacters++;
        }
        final String zFormatSpec = "%0" + maxZCharacters + ".1f";

        final double zScale = scapeParameters.zScale == null ? 0.0 : scapeParameters.zScale / renderScale;

        final List<RenderSection> renderSectionList = new ArrayList<>(sectionDataList.size());

        RenderSection currentRenderSection = null;
        double currentZ = -1;

        for (final SectionData sectionData : sectionDataList) {

            if ((currentRenderSection == null) || (sectionData.getZ() - zScale >= currentZ)) {
                currentZ = sectionData.getZ();
                currentRenderSection = new RenderSection(currentZ,
                                                         renderSectionList.size(),
                                                         zFormatSpec,
                                                         scapeParameters.maxImagesPerDirectory,
                                                         sectionRootDirectory);
                renderSectionList.add(currentRenderSection);
            }

            final double minX = scapeParameters.getEffectiveBound(sectionData.getMinX(),
                                                                  stackBounds.getMinX(),
                                                                  scapeParameters.minX);
            final double minY = scapeParameters.getEffectiveBound(sectionData.getMinY(),
                                                                  stackBounds.getMinY(),
                                                                  scapeParameters.minY);

            final SectionData boundedSectionData =
                    new SectionData(sectionData.getSectionId(),
                                    sectionData.getZ(),
                                    sectionData.getTileCount(),
                                    minX,
                                    scapeParameters.getEffectiveBound(sectionData.getMaxX(),
                                                                      stackBounds.getMaxX(),
                                                                      scapeParameters.getMaxX(minX)),
                                    minY,
                                    scapeParameters.getEffectiveBound(sectionData.getMaxY(),
                                                                      stackBounds.getMaxY(),
                                                                      scapeParameters.getMaxY(minY)));

            final long scaledSectionWidth = (long) (boundedSectionData.getWidth() * renderScale + 0.5);
            final long scaledSectionHeight = (long) (boundedSectionData.getHeight() * renderScale + 0.5);
            final long sectionPixelCount = scaledSectionWidth * scaledSectionHeight;

            if (sectionPixelCount >= Integer.MAX_VALUE) {
                final DecimalFormat formatter = new DecimalFormat("#,###");
                throw new IllegalArgumentException("section " + boundedSectionData + " has " +
                                                   formatter.format(sectionPixelCount) + " pixels at scale " +
                                                   renderScale + " which is greater than the maximum allowed " +
                                                   formatter.format(Integer.MAX_VALUE));
            }

            currentRenderSection.addSection(boundedSectionData);
        }

        return renderSectionList;
    }

    @Nonnull
    private static Function<RenderSection, Integer> buildSectionScapeGenerationFunction(final String baseDataUrl,
                                                                                        final StackId stackId,
                                                                                        final ScapeParameters scape,
                                                                                        final double renderScale,
                                                                                        final boolean isTiffWithResolutionOutput,
                                                                                        final List<Double> stackResolutionValues) {
        return renderSection -> {

            final Double z = renderSection.getFirstZ();
            LogUtilities.setupExecutorLog4j("z " + z);

            final RenderDataClient workerDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());

            // set cache size to 50MB so that masks get cached but most of RAM is left for target image
            final int maxCachedPixels = 50 * 1000000;
            final ImageProcessorCache imageProcessorCache =
                    new ImageProcessorCache(maxCachedPixels, false, false);

            final boolean isProjectionNeeded = renderSection.isProjectionNeeded();
            BufferedImage sectionImage = null;
            ImageStack projectedStack = null;

            for (final SectionData sectionData : renderSection.getSectionDataList()) {

                final String parametersUrl =
                        workerDataClient.getRenderParametersUrlString(stackId.getStack(),
                                                                      sectionData.getMinX(),
                                                                      sectionData.getMinY(),
                                                                      sectionData.getZ(),
                                                                      sectionData.getWidth(),
                                                                      sectionData.getHeight(),
                                                                      renderScale,
                                                                      scape.filterListName);

                LOG.debug("generateScapeFunction: loading {}", parametersUrl);

                final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
                renderParameters.setFillWithNoise(scape.fillWithNoise);
                renderParameters.setDoFilter(scape.doFilter);
                renderParameters.setChannels(scape.channels);

                sectionImage = renderParameters.openTargetImage();

                if (isProjectionNeeded && (projectedStack == null)) {
                    projectedStack = new ImageStack(sectionImage.getWidth(), sectionImage.getHeight());
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

            final File sectionFile = renderSection.getOutputFile(scape.format);

            if (isTiffWithResolutionOutput) {

                Utils.saveTiffImageWithResolution(sectionImage,
                                                  stackResolutionValues,
                                                  scape.resolutionUnit,
                                                  renderScale,
                                                  sectionFile.getAbsolutePath());

            } else {

                Utils.saveImage(sectionImage,
                                sectionFile.getAbsolutePath(),
                                scape.format,
                                true,
                                0.85f);

            }

            return 1;
        };
    }

    public static class RenderSection implements Serializable {

        private final Double firstZ;
        private final List<SectionData> sectionDataList;
        private final File outputDir;
        private final String zFormatSpec;

        RenderSection(final Double firstZ,
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

        List<SectionData> getSectionDataList() {
            return sectionDataList;
        }

        Double getFirstZ() {
            return firstZ;
        }

        boolean isProjectionNeeded() {
            return sectionDataList.size() > 1;
        }

        File getOutputFile(final String fileExtension) {
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

        void addSection(final SectionData sectionData) {
            this.sectionDataList.add(sectionData);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScapeClient.class);
}
