package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;
import ij.io.Opener;
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
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MipmapParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
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

        @Parameter(
                description = "Z values for layers to render",
                required = true)
        public List<Double> zValues;

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
                for (final Double z : parameters.zValues) {
                    client.processMipmapsForZ(z);
                }
                client.updateMipmapPathBuilderForStack();
            }
        };
        clientRunner.run();
    }

    private final MipmapParameters parameters;

    private final String stack;
    private final MipmapPathBuilder mipmapPathBuilder;
    private final RenderDataClient renderDataClient;

    public MipmapClient(final RenderWebServiceParameters renderWebParameters,
                        final MipmapParameters parameters)
            throws IOException {

        this.parameters = parameters;
        this.stack = parameters.stack;

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

    public void updateMipmapPathBuilderForStack()
            throws IOException {

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
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
                    LOG.error("updateMipmapPathBuilderForStack: skipping update because " +
                              "old and new mipmap path builders have different root path or extension " +
                              "( old={}, new={} ).", currentBuilder, mipmapPathBuilder);
                    updatedBuilder = null; // skip update
                }

            } else {
                updatedBuilder = mipmapPathBuilder;
            }

            if (updatedBuilder != null) {
                renderDataClient.setMipmapPathBuilder(parameters.stack, updatedBuilder);
            } else {
                LOG.info("updateMipmapPathBuilderForStack: builder is already up-to-date");
            }

        } else {
            throw new IOException("Version is missing for stack " + parameters.stack + ".");
        }

    }

    public int processMipmapsForZ(final Double z)
            throws Exception {

        LOG.info("processMipmapsForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);
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
            LOG.info("processMipmapsForZ: exit, removed {} mipmaps for {} tiles with z {} in group {} ({} to {} of {})",
                     removedFileCount, renderedTileCount, z, parameters.renderGroup, startTile, stopTile - 1, tileCount);
        } else {
            LOG.info("processMipmapsForZ: exit, generated mipmaps for {} tiles with z {} in group {} ({} to {} of {})",
                     renderedTileCount, z, parameters.renderGroup, startTile, stopTile - 1, tileCount);
        }

        return renderedTileCount;
    }

    void generateMissingMipmapFiles(final TileSpec tileSpec)
            throws IllegalArgumentException, IOException {

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

            final String channelName = channelSpec.getName();
            final String context;
            if (channelName == null) {
                context = "tile '" + tileSpec.getTileId() + "'";
            } else {
                context = "channel '" + channelName + "' in tile '" + tileSpec.getTileId() + "'";
            }

            final Map.Entry<Integer, ImageAndMask> firstEntry = channelSpec.getFirstMipmapEntry();
            if (firstEntry == null) {
                throw new IllegalArgumentException("first entry mipmap is missing from " + context);
            }

            final ImageAndMask sourceImageAndMask = firstEntry.getValue();

            if ((sourceImageAndMask == null) || (!sourceImageAndMask.hasImage())) {
                throw new IllegalArgumentException("first entry mipmap image is missing from " + context);
            }

            if (parameters.forceGeneration || isMissingMipmaps(channelSpec, firstEntry, sourceImageAndMask.hasMask())) {

                ImageProcessor sourceImageProcessor = loadImageProcessor(sourceImageAndMask.getImageUrl());

                ImageProcessor sourceMaskProcessor = null;
                if (sourceImageAndMask.hasMask()) {
                    sourceMaskProcessor = loadImageProcessor(sourceImageAndMask.getMaskUrl());
                }

                Map.Entry<Integer, ImageAndMask> derivedEntry;
                ImageAndMask derivedImageAndMask;
                File imageMipmapFile;
                File maskMipmapFile;
                for (int mipmapLevel = 1; mipmapLevel <= mipmapPathBuilder.getNumberOfLevels(); mipmapLevel++) {

                    derivedEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, firstEntry, false);
                    derivedImageAndMask = derivedEntry.getValue();

                    if (! channelSpec.hasMipmap(mipmapLevel)) {

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
                LOG.info("generateMissingMipmapFiles: all mipmap files exist for ", context);
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

            if (! channelSpec.hasMipmap(mipmapLevel)) {

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

    static ImageProcessor loadImageProcessor(final String url)
            throws IllegalArgumentException {

        // openers keep state about the file being opened, so we need to create a new opener for each load
        final Opener opener = new Opener();
        opener.setSilentMode(true);

        final ImagePlus imagePlus = opener.openURL(url);
        if (imagePlus == null) {
            throw new IllegalArgumentException("failed to create imagePlus instance for '" + url + "'");
        }

        return imagePlus.getProcessor();
    }

// This method to calculate a zip file digest was copied here from prior mipmap generator code.
// It was used to identify common TrakEM2 masks "hidden" in differently named zip files.
// Since we don't currently need this functionality, the code is commented-out here.
//
//    private String getDigest(final File file)
//            throws IOException {
//
//        messageDigest.reset();
//
//        ZipFile zipFile = null;
//        InputStream inputStream = null;
//        try {
//
//            if (file.getAbsolutePath().endsWith(".zip")) {
//                zipFile = new ZipFile(file);
//                final Enumeration<? extends ZipEntry> e = zipFile.entries();
//                if (e.hasMoreElements()) {
//                    final ZipEntry zipEntry = e.nextElement();
//                    // only use unzipped input stream if the zipped file contains a single mask
//                    if (! e.hasMoreElements()) {
//                        inputStream = zipFile.getInputStream(zipEntry);
//                    }
//                }
//            }
//
//            if (inputStream == null) {
//                inputStream = new FileInputStream(file);
//            }
//
//            final byte[] bytes = new byte[2048];
//            int numBytes;
//            while ((numBytes = inputStream.read(bytes)) != -1) {
//                messageDigest.update(bytes, 0, numBytes);
//            }
//
//        } finally {
//            if (inputStream != null) {
//                try {
//                    inputStream.close();
//                } catch (final IOException e) {
//                    LOG.warn("failed to close " + file.getAbsolutePath() + ", ignoring error", e);
//                }
//            }
//            if (zipFile != null) {
//                try {
//                    zipFile.close();
//                } catch (final IOException e) {
//                    LOG.warn("failed to close zip file " + file.getAbsolutePath() + ", ignoring error", e);
//                }
//            }
//        }
//
//        final byte[] digest = messageDigest.digest();
//
//        // create string representation of digest that matches output generated by tools like md5sum
//        final BigInteger bigInt = new BigInteger(1, digest);
//        return bigInt.toString(16);
//    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClient.class);
}
