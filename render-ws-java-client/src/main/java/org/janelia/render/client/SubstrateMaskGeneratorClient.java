package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.Utils;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating substrate masks by thresholding source pixel values.
 *
 * @author Eric Trautman
 */
public class SubstrateMaskGeneratorClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack (default is same as source stack)")
        public String targetStack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for masks (e.g. /nrs/hess/render/masks)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--minRetainedIntensity",
                description = "Mask out pixels with intensities less than this value " +
                              "(omit if low intensity values should not be masked)"
        )
        public Double minRetainedIntensity;

        @Parameter(
                names = "--maxRetainedIntensity",
                description = "Mask out pixels with intensities greater than this value " +
                              "(omit if high intensity values should not be masked)"
        )
        public Double maxRetainedIntensity;

        @Parameter(
                names = "--format",
                description = "Format for masks (tiff, jpg, png)"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--z",
                description = "Mask all tiles with these Z values",
                variableArity = true)
        public List<Double> zValues;

        @Parameter(
                names = "--tileIdJson",
                description = "JSON file containing array of explicit tileIds to be masked (.json, .gz, or .zip)"
        )
        public String tileIdJson;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete target stack after updating all masks",
                arity = 0)
        public boolean completeTargetStack = false;

        public String getTargetOwner() {
            return targetOwner == null ? renderWeb.owner : targetOwner;
        }

        public String getTargetProject() {
            return targetProject == null ? renderWeb.project : targetProject;
        }

        public String getTargetStack() {
            return targetStack == null ? stack : targetStack;
        }

        public boolean isRetainedPixelValue(final float pixelValue) {
            return ((minRetainedIntensity == null) || (pixelValue >= minRetainedIntensity)) &&
                   ((maxRetainedIntensity == null) || (pixelValue <= maxRetainedIntensity));
        }

        public String getBaseMaskDirectoryPath() {
            // min_a, max_b, a_to_b
            final String minMaxName;
            if (minRetainedIntensity == null) {
                minMaxName = "max_" + formatIntensityName(maxRetainedIntensity);
            } else if (maxRetainedIntensity == null) {
                minMaxName = "min_" + formatIntensityName(minRetainedIntensity);
            } else {
                minMaxName = formatIntensityName(minRetainedIntensity) + "_to_" +
                             formatIntensityName(maxRetainedIntensity);
            }
            return Paths.get(rootDirectory, getTargetProject(), getTargetStack(), minMaxName)
                    .toAbsolutePath().toString();
        }

        public String formatIntensityName(final Double value) {
            final boolean isValueIntegral = (value == Math.floor(value)) && !Double.isInfinite(value);
            return isValueIntegral ? String.valueOf(value.intValue()) : value.toString();
        }
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final SubstrateMaskGeneratorClient client = new SubstrateMaskGeneratorClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient sourceDataClient;
    private final RenderDataClient targetDataClient;
    private final ImageProcessorCache originalMaskCache;
    private final List<String> tileIdList;

    public SubstrateMaskGeneratorClient(final Parameters parameters)
            throws IllegalArgumentException, IOException {

        if ((parameters.minRetainedIntensity == null) && (parameters.maxRetainedIntensity == null)) {
            throw new IllegalArgumentException("must specify --minRetainedIntensity or --maxRetainedIntensity");
        }

        if (parameters.tileIdJson != null) {
            final JsonUtils.Helper<String> jsonHelper = new JsonUtils.Helper<>(String.class);
            try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(parameters.tileIdJson)) {
                this.tileIdList = jsonHelper.fromJsonArray(reader);
            }
            LOG.info("ctor: loaded {} tile ids from {}", tileIdList.size(), parameters.tileIdJson);
        } else {
            this.tileIdList = new ArrayList<>();
        }

        final int zCount = parameters.zValues == null ? 0 : parameters.zValues.size();
        if ((zCount == 0) && (tileIdList.size() == 0)) {
            throw new IllegalArgumentException("no tiles to process, must specify --z or --tileIdJson");
        }

        this.parameters = parameters;
        this.sourceDataClient = parameters.renderWeb.getDataClient();
        this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                     parameters.getTargetOwner(),
                                                     parameters.getTargetProject());
        final long maxCachedPixels = 200 * 1000000; // 200 MB
        this.originalMaskCache = new ImageProcessorCache(maxCachedPixels,
                                                         false,
                                                         false);
    }

    public void run()
            throws IOException {

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());

        if (parameters.zValues != null) {
            for (final Double z : parameters.zValues) {
                generateMasksForZ(z, null);
            }
        }

        generateMasksForExplicitTiles();

        if (parameters.completeTargetStack) {
            targetDataClient.setStackState(parameters.getTargetStack(), StackMetaData.StackState.COMPLETE);
        }
    }

    public void generateMasksForZ(final Double z,
                                  final Set<String> explicitTileIds)
            throws IOException {

        final int explicitTileCount = explicitTileIds == null ? 0 : explicitTileIds.size();

        LOG.info("generateMasksForZ: entry, z={}, explicitTileCount={}", z, explicitTileCount);

        final ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(parameters.stack, z);

        if (explicitTileCount > 0) {
            resolvedTiles.removeDifferentTileSpecs(explicitTileIds);
        }

        final Path maskDirectoryPath = Paths.get(parameters.getBaseMaskDirectoryPath(),
                                                 String.valueOf(z.intValue()));
        Files.createDirectories(maskDirectoryPath);
        final String maskDirectoryPathString = maskDirectoryPath.toString();

        LOG.info("generateMasksForZ: created {}", maskDirectoryPathString);

        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            generateMask(tileSpec, maskDirectoryPathString);
        }

        targetDataClient.saveResolvedTiles(resolvedTiles, parameters.getTargetStack(), z);

        LOG.info("generateMasksForZ: exit");
    }

    public void generateMasksForExplicitTiles()
            throws IOException {

        LOG.info("generateMasksForExplicitTiles: entry");

        if ((tileIdList != null) && (tileIdList.size() > 0)) {
            final List<TileSpec> tileSpecs = sourceDataClient.getTileSpecsWithIds(tileIdList, parameters.stack);

            final Map<Double, Set<String>> zToTileIdsMap = new HashMap<>();
            for (final TileSpec tileSpec : tileSpecs) {
                final Double tileZ = tileSpec.getZ();
                final Set<String> tileSpecsForZ = zToTileIdsMap.computeIfAbsent(tileZ, z -> new HashSet<>());
                tileSpecsForZ.add(tileSpec.getTileId());
            }

            for (final Double z : zToTileIdsMap.keySet().stream().sorted().collect(Collectors.toList())) {
                generateMasksForZ(z, zToTileIdsMap.get(z));
            }
        }

        LOG.info("generateMasksForExplicitTiles: exit");
    }

    void generateMask(final TileSpec tileSpec,
                      final String maskDirectoryPathString)
            throws IllegalArgumentException, IOException {

        final String tileId = tileSpec.getTileId();

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

            final String channelName = channelSpec.getName();
            final String maskFileName;
            if (channelName == null) {
                maskFileName = tileSpec.getTileId() + ".mask." + parameters.format;
            } else {
                maskFileName = tileSpec.getTileId() + "." +
                               channelName.replaceAll("\\s", "-") +
                               ".mask." + parameters.format;
            }

            final Map.Entry<Integer, ImageAndMask> firstEntry = channelSpec.getFirstMipmapEntry();
            final ImageAndMask sourceImageAndMask = channelSpec.getFirstMipmapImageAndMask(tileId);

            final ImageProcessor sourceImageProcessor =
                    ImageProcessorCache.DISABLED_CACHE.get(sourceImageAndMask.getImageUrl(),
                                                           0,
                                                           false,
                                                           false,
                                                           sourceImageAndMask.getImageLoaderType(),
                                                           sourceImageAndMask.getImageSliceNumber());

            byte[] originalMaskPixels = null;
            if (sourceImageAndMask.hasMask()) {
                originalMaskPixels = (byte[])
                        originalMaskCache.get(sourceImageAndMask.getMaskUrl(),
                                              0,
                                              true,
                                              false,
                                              sourceImageAndMask.getMaskLoaderType(),
                                              sourceImageAndMask.getMaskSliceNumber()).getPixels();
            }


            final int width = sourceImageProcessor.getWidth();
            final int height = sourceImageProcessor.getHeight();
            final int pixelCount = sourceImageProcessor.getPixelCount();

            final ByteProcessor newMaskProcessor = new ByteProcessor(width, height);

            int retainedPixelCount = 0;
            for (int i = 0; i < pixelCount; i++) {
                if ((originalMaskPixels == null) || (originalMaskPixels[i] > 0)) {
                    // not masked by original mask
                    final float sourcePixel = sourceImageProcessor.getf(i);
                    if (parameters.isRetainedPixelValue(sourcePixel)) {
                        newMaskProcessor.set(i, 255);
                        retainedPixelCount++;
                    }
                }
            }

            final int maskedPixelCount = pixelCount - retainedPixelCount;
            LOG.info("generateMask: masked {} out of {} pixels for tile {}",
                     maskedPixelCount, pixelCount, tileSpec.getTileId());

            final String newMaskPathString = Paths.get(maskDirectoryPathString, maskFileName).toString();

            final ImageAndMask updatedImageAndMask = new ImageAndMask(sourceImageAndMask.getImageUrl(),
                                                                      sourceImageAndMask.getImageLoaderType(),
                                                                      sourceImageAndMask.getImageSliceNumber(),
                                                                      newMaskPathString,
                                                                      null,
                                                                      null);

            channelSpec.putMipmap(firstEntry.getKey(), updatedImageAndMask);

            final BufferedImage image = newMaskProcessor.getBufferedImage();
            Utils.saveImage(image,
                            newMaskPathString,
                            parameters.format,
                            false,
                            0.85f);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SubstrateMaskGeneratorClient.class);
}
