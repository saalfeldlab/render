package org.janelia.render.client;

import com.beust.jcommander.ParametersDelegate;

import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MipmapParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.StackIdWithZParameters.StackIdWithZ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating mipmap files into a {@link org.janelia.alignment.spec.stack.MipmapPathBuilder}
 * directory structure.
 *
 * @author Eric Trautman
 */
public class MipmapClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public MipmapParameters mipmap = new MipmapParameters();
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

                final MipmapClient client = new MipmapClient(parameters.renderWeb,
                                                             parameters.mipmap);
                client.processMipmaps();
            }
        };
        clientRunner.run();
    }

    private final MipmapParameters parameters;
    private final MipmapPathBuilder mipmapPathBuilder;
    private final RenderDataClient renderDataClient;

    public MipmapClient(final RenderWebServiceParameters renderWebParameters,
                        final MipmapParameters mipmapParameters)
            throws IOException {

        this.parameters = mipmapParameters;

        this.mipmapPathBuilder = parameters.getMipmapPathBuilder();

        if (parameters.renderGroup != null) {

            if (parameters.numberOfRenderGroups == null) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups must be specified when renderGroup is specified");
            }

            if (parameters.renderGroup < 1) {
                throw new IllegalArgumentException("renderGroup values start at 1");
            }

            if (parameters.renderGroup > parameters.numberOfRenderGroups) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups (" + parameters.numberOfRenderGroups +
                        ") must be greater than the renderGroup (" + parameters.renderGroup + ")");
            }

        } else if (parameters.numberOfRenderGroups != null) {
            throw new IllegalArgumentException(
                    "renderGroup (1-n) must be specified when numberOfRenderGroups are specified");
        }

        this.renderDataClient = renderWebParameters.getDataClient();

    }

    MipmapPathBuilder getMipmapPathBuilder() {
        return mipmapPathBuilder;
    }

    public void updateMipmapPathBuilderForStack(final StackId stackId)
            throws IOException {

        final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
        final StackMetaData stackMetaData = projectClient.getStackMetaData(stackId.getStack());
        final StackVersion stackVersion = stackMetaData.getCurrentVersion();

        if (stackVersion != null) {
            final MipmapPathBuilder updatedBuilder;
            final MipmapPathBuilder currentBuilder = stackVersion.getMipmapPathBuilder();
            if (currentBuilder != null) {
                if (currentBuilder.hasSamePathAndExtension(mipmapPathBuilder)) {
                    if (mipmapPathBuilder.getNumberOfLevels() > currentBuilder.getNumberOfLevels()) {
                        updatedBuilder = mipmapPathBuilder;
                    } else {
                        updatedBuilder = null; // no need to update
                    }
                } else {
                    LOG.error("updateMipmapPathBuilderForStack: skipping update for {} because " +
                              "old and new mipmap path builders have different root path or extension " +
                              "( old={}, new={} ).", stackId, currentBuilder, mipmapPathBuilder);
                    updatedBuilder = null; // skip update
                }

            } else {
                updatedBuilder = mipmapPathBuilder;
            }

            if (updatedBuilder != null) {
                projectClient.setMipmapPathBuilder(stackId.getStack(), updatedBuilder);
            } else {
                LOG.info("updateMipmapPathBuilderForStack: builder for {} is already up-to-date", stackId);
            }

        } else {
            throw new IOException("Version is missing for " + stackId + ".");
        }

    }

    public void processMipmaps()
            throws Exception {
        StackIdWithZ previousStackIdWithZ = null;
        for (final StackIdWithZ stackIdWithZ : parameters.stackId.getStackIdWithZList(renderDataClient)) {
            if ((previousStackIdWithZ != null) && (! previousStackIdWithZ.stackId.equals(stackIdWithZ.stackId))) {
                updateMipmapPathBuilderForStack(previousStackIdWithZ.stackId);
            }
            processMipmapsForZ(stackIdWithZ.stackId, stackIdWithZ.z);
            previousStackIdWithZ = stackIdWithZ;
        }
        if (previousStackIdWithZ != null) {
            updateMipmapPathBuilderForStack(previousStackIdWithZ.stackId);
        }
    }

    public int processMipmapsForZ(final StackId stackId,
                                  final Double z)
            throws Exception {

        LOG.info("processMipmapsForZ: entry, stackId={}, z={}", stackId, z);

        final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
        final ResolvedTileSpecCollection tiles = projectClient.getResolvedTiles(stackId.getStack(), z);
        final int tileCount = tiles.getTileCount();
        final int tilesPerGroup = (int) Math.ceil((double) tileCount / parameters.numberOfRenderGroups);
        final int startTile = (parameters.renderGroup - 1) * tilesPerGroup;
        final int stopTile = startTile + tilesPerGroup ;

        int removedFileCount = 0;

        final List<TileSpec> tileSpecsToRender = new ArrayList<>(tiles.getTileSpecs()).subList(startTile, stopTile);
        for (final TileSpec tileSpec : tileSpecsToRender) {
            if (parameters.removeAll) {
                removedFileCount += removeExistingMipmapFiles(tileSpec);
            } else {
                generateMissingMipmapFiles(tileSpec);
            }
        }

        final int renderedTileCount = tileSpecsToRender.size();

        if (parameters.removeAll) {
            LOG.info("processMipmapsForZ: exit, for {} removed {} mipmaps for {} tiles with z {} in group {} ({} to {} of {})",
                     stackId, removedFileCount, renderedTileCount, z, parameters.renderGroup, startTile, stopTile - 1, tileCount);
        } else {
            LOG.info("processMipmapsForZ: exit, for {} generated mipmaps for {} tiles with z {} in group {} ({} to {} of {})",
                     stackId, renderedTileCount, z, parameters.renderGroup, startTile, stopTile - 1, tileCount);
        }

        return renderedTileCount;
    }

    void generateMissingMipmapFiles(final TileSpec tileSpec)
            throws IllegalArgumentException, IOException {

        final String tileId = tileSpec.getTileId();

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

            final Map.Entry<Integer, ImageAndMask> firstEntry = channelSpec.getFirstMipmapEntry();
            final ImageAndMask sourceImageAndMask = channelSpec.getFirstMipmapImageAndMask(tileId);

            if (parameters.forceGeneration || isMissingMipmaps(channelSpec, firstEntry, sourceImageAndMask.hasMask())) {

                ImageProcessor sourceImageProcessor =
                        ImageProcessorCache.DISABLED_CACHE.get(sourceImageAndMask.getImageUrl(),
                                                               0,
                                                               false,
                                                               false,
                                                               sourceImageAndMask.getImageLoaderType(),
                                                               sourceImageAndMask.getImageSliceNumber());

                ImageProcessor sourceMaskProcessor = null;
                if (sourceImageAndMask.hasMask()) {
                    sourceMaskProcessor =
                            ImageProcessorCache.DISABLED_CACHE.get(sourceImageAndMask.getMaskUrl(),
                                                                   0,
                                                                   true,
                                                                   false,
                                                                   sourceImageAndMask.getMaskLoaderType(),
                                                                   sourceImageAndMask.getMaskSliceNumber());
                }

                Map.Entry<Integer, ImageAndMask> derivedEntry;
                ImageAndMask derivedImageAndMask;
                File imageMipmapFile;
                File maskMipmapFile;
                for (int mipmapLevel = 1; mipmapLevel <= mipmapPathBuilder.getNumberOfLevels(); mipmapLevel++) {

                    derivedEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, firstEntry, false);
                    derivedImageAndMask = derivedEntry.getValue();

                    if (channelSpec.isMissingMipmap(mipmapLevel)) {

                        final boolean isMipmapLevelInRange = mipmapLevel >= parameters.minLevel;

                        if (isMipmapLevelInRange) {
                            createMissingDirectories(derivedImageAndMask.getImageUrl());
                        }

                        imageMipmapFile = getFileForUrlString(derivedImageAndMask.getImageUrl());
                        sourceImageProcessor = generateMipmapFile(sourceImageProcessor,
                                                                  imageMipmapFile,
                                                                  channelSpec.getMinIntensity(),
                                                                  channelSpec.getMaxIntensity(),
                                                                  isMipmapLevelInRange);

                        if (sourceImageAndMask.hasMask()) {
                            if (isMipmapLevelInRange) {
                                createMissingDirectories(derivedImageAndMask.getMaskUrl());
                            }
                            maskMipmapFile = getFileForUrlString(derivedImageAndMask.getMaskUrl());
                            sourceMaskProcessor = generateMipmapFile(sourceMaskProcessor,
                                                                     maskMipmapFile,
                                                                     channelSpec.getMinIntensity(),
                                                                     channelSpec.getMaxIntensity(),
                                                                     isMipmapLevelInRange);
                        }

                    }
                }

            } else {
                LOG.info("generateMissingMipmapFiles: all mipmap files exist for {}", channelSpec.getContext(tileId));
            }
        }
    }

    private int removeExistingMipmapFiles(final TileSpec tileSpec)
            throws IllegalArgumentException, IOException {

        int removedFileCount = 0;

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

            final Map.Entry<Integer, ImageAndMask> firstEntry = channelSpec.getFirstMipmapEntry();
            if (firstEntry != null) {

                final ImageAndMask sourceImageAndMask = firstEntry.getValue();
                if ((sourceImageAndMask != null) && sourceImageAndMask.hasImage()) {

                    Map.Entry<Integer, ImageAndMask> derivedEntry;
                    ImageAndMask derivedImageAndMask;
                    File imageMipmapFile;
                    File maskMipmapFile;
                    for (int mipmapLevel = 1; mipmapLevel <= mipmapPathBuilder.getNumberOfLevels(); mipmapLevel++) {

                        derivedEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, firstEntry, false);
                        derivedImageAndMask = derivedEntry.getValue();

                        imageMipmapFile = getFileForUrlString(derivedImageAndMask.getImageUrl());
                        if (imageMipmapFile.exists()) {
                            Files.delete(imageMipmapFile.toPath());
                            removedFileCount++;
                        }

                        if (sourceImageAndMask.hasMask()) {
                            maskMipmapFile = getFileForUrlString(derivedImageAndMask.getMaskUrl());
                            if (maskMipmapFile.exists()) {
                                Files.delete(maskMipmapFile.toPath());
                                removedFileCount++;
                            }
                        }
                    }
                }
            }

        }

        return removedFileCount;
    }

    private boolean isMissingMipmaps(final ChannelSpec channelSpec,
                                     final Map.Entry<Integer, ImageAndMask> firstEntry,
                                     final boolean hasMask) {

        boolean foundMissingMipmap = false;

        Map.Entry<Integer, ImageAndMask> derivedEntry;
        ImageAndMask derivedImageAndMask;
        File imageMipmapFile;
        File maskMipmapFile;
        for (int mipmapLevel = 1; mipmapLevel <= mipmapPathBuilder.getNumberOfLevels(); mipmapLevel++) {

            derivedEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, firstEntry, false);
            derivedImageAndMask = derivedEntry.getValue();

            if (channelSpec.isMissingMipmap(mipmapLevel)) {

                imageMipmapFile = getFileForUrlString(derivedImageAndMask.getImageUrl());
                if (! imageMipmapFile.exists()) {
                    foundMissingMipmap = true;
                    break;
                }

                if (hasMask) {
                    maskMipmapFile = getFileForUrlString(derivedImageAndMask.getMaskUrl());
                    if (! maskMipmapFile.exists()) {
                        foundMissingMipmap = true;
                        break;
                    }
                }

            }
        }

        return foundMissingMipmap;
    }

    private void createMissingDirectories(final String fileUrl)
            throws IllegalArgumentException, IOException {
        final File sourceFile = getFileForUrlString(fileUrl);
        final File sourceDirectory = sourceFile.getParentFile().getCanonicalFile();
        if (! sourceDirectory.exists()) {
            if (! sourceDirectory.mkdirs()) {
                if (! sourceDirectory.exists()) {
                    throw new IllegalArgumentException("failed to create directory " +
                                                       sourceDirectory.getAbsolutePath());
                }
            }
        }
    }

    private File getFileForUrlString(final String url) {
        final URI uri = Utils.convertPathOrUriStringToUri(url);
        return new File(uri);
    }

    private ImageProcessor generateMipmapFile(final ImageProcessor sourceProcessor,
                                              final File targetMipmapFile,
                                              final double minIntensity,
                                              final double maxIntensity,
                                              final boolean isMipmapLevelInRange)
            throws IOException {

        final int mipmapLevelDelta = 1;
        final ImageProcessor downSampledProcessor = Downsampler.downsampleImageProcessor(sourceProcessor,
                                                                                         mipmapLevelDelta);
        if (isMipmapLevelInRange && (parameters.forceGeneration || (! targetMipmapFile.exists()))) {
            final BufferedImage image = getGrayBufferedImage(downSampledProcessor, minIntensity, maxIntensity);
            Utils.saveImage(image,
                            targetMipmapFile.getAbsolutePath(),
                            parameters.format,
                            false,
                            0.85f);
        }

        return downSampledProcessor;
    }

    private static BufferedImage getGrayBufferedImage(final ImageProcessor downSampledProcessor,
                                                      final double minIntensity,
                                                      final double maxIntensity) {

        downSampledProcessor.setMinAndMax(minIntensity, maxIntensity);

        final BufferedImage image;
        final ImageProcessor p;
        if (maxIntensity < 256) {
            p = downSampledProcessor.convertToByteProcessor();
            image = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        } else {
            p = downSampledProcessor.convertToShortProcessor();
            image = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_USHORT_GRAY);
        }

        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, p.getWidth(), p.getHeight(), p.getPixels());

        return image;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClient.class);
}
