package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating mipmap files into a {@link org.janelia.alignment.spec.stack.MipmapPathBuilder}
 * directory structure.
 *
 * @author Eric Trautman
 */
public class MipmapClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--rootDirectory", description = "Root directory for mipmaps (e.g. /tier2/flyTEM/nobackup/rendered_mipmaps/FAFB00)", required = true)
        private String rootDirectory;

        @Parameter(names = "--maxLevel", description = "Maximum mipmap level to generate (default is 6)", required = false)
        private Integer maxLevel = 6;

        @Parameter(names = "--format", description = "Format for mipmaps (default is TIFF)", required = false)
        private String format = Utils.TIFF_FORMAT;

        @Parameter(names = "--forceGeneration", description = "Regenerate mipmaps even if they already exist", required = false, arity = 0)
        private boolean forceGeneration = false;

        @Parameter(names = "--renderGroup", description = "Index (1-n) that identifies portion of layer to render (omit if only one job is being used)", required = false)
        private Integer renderGroup = 1;

        @Parameter(names = "--numberOfRenderGroups", description = "Total number of parallel jobs being used to render this layer (omit if only one job is being used)", required = false)
        private Integer numberOfRenderGroups = 1;

        @Parameter(description = "Z values for layers to render", required = true)
        private List<Double> zValues;

        public Parameters() {
        }

        /**
         * Constructor for testing.
         *
         * @param  rootDirectory  root directory for rendered tiles.
         */
        protected Parameters(final String rootDirectory,
                             final int maxLevel) {
            this.rootDirectory = rootDirectory;
            this.maxLevel = maxLevel;
            this.zValues = new ArrayList<>();
        }

        /**
         * Maps 'tiff' format to 'tif' extension so that {@link ij.io.Opener#openURL(String)} method will work.
         *
         * @return mapped extension for format
         */
        public String getExtension() {
            String extension = format;
            // map 'tiff' format to 'tif' extension so that
            if (Utils.TIFF_FORMAT.equals(format)) {
                extension = "tif";
            }
            return extension;
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
                parameters.parse(args, MipmapClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MipmapClient client = new MipmapClient(parameters);
                for (final Double z : parameters.zValues) {
                    client.generateMipmapsForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters params;

    private final String stack;
    private final MipmapPathBuilder mipmapPathBuilder;
    private final RenderDataClient renderDataClient;

    public MipmapClient(final Parameters params)
            throws IOException {

        this.params = params;
        this.stack = params.stack;

        final File rootDirectory = new File(params.rootDirectory).getCanonicalFile();
        if (! rootDirectory.exists()) {
            throw new IllegalArgumentException("missing root directory " + rootDirectory);
        }

        if (! rootDirectory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to root directory " + rootDirectory);
        }

        this.mipmapPathBuilder = new MipmapPathBuilder(rootDirectory.getPath(), params.maxLevel, params.getExtension());

        if (params.renderGroup != null) {

            if (params.numberOfRenderGroups == null) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups must be specified when renderGroup is specified");
            }

            if (params.renderGroup < 1) {
                throw new IllegalArgumentException("renderGroup values start at 1");
            }

            if (params.renderGroup > params.numberOfRenderGroups) {
                throw new IllegalArgumentException(
                        "numberOfRenderGroups (" + params.numberOfRenderGroups +
                        ") must be greater than the renderGroup (" + params.renderGroup + ")");
            }

        } else if (params.numberOfRenderGroups != null) {
            throw new IllegalArgumentException(
                    "renderGroup (1-n) must be specified when numberOfRenderGroups are specified");
        }

        this.renderDataClient = params.getClient();
    }

    public MipmapPathBuilder getMipmapPathBuilder() {
        return mipmapPathBuilder;
    }

    public void generateMipmapsForZ(final Double z)
            throws Exception {

        LOG.info("generateMipmapsForZ: {}, entry, dataClient={}",
                 z, renderDataClient);

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(stack, z);
        final int tileCount = tiles.getTileCount();
        final int tilesPerGroup = (tileCount / params.numberOfRenderGroups) + 1;
        final int startTile = (params.renderGroup - 1) * tilesPerGroup;
        final int stopTile = startTile + tilesPerGroup - 1;

        int count = 0;
        for (final TileSpec tileSpec : tiles.getTileSpecs()) {
            if ((count >= startTile) && (count < stopTile)) {
                generateMissingMipmapFiles(tileSpec);
            }
            count++;
        }

        LOG.info("generateMipmapsForZ: {}, exit", z);
    }

    public void generateMissingMipmapFiles(final TileSpec tileSpec)
            throws IllegalArgumentException, IOException {

        final Map.Entry<Integer, ImageAndMask> firstEntry = tileSpec.getFirstMipmapEntry();
        if (firstEntry == null) {
            throw new IllegalArgumentException("first entry mipmap is missing from tile '" +
                                               tileSpec.getTileId() + "'");
        }

        final ImageAndMask sourceImageAndMask = firstEntry.getValue();

        if ((sourceImageAndMask == null) || (! sourceImageAndMask.hasImage())) {
            throw new IllegalArgumentException("first entry mipmap image is missing from tile '" +
                                               tileSpec.getTileId() + "'");
        }

        if (params.forceGeneration || isMissingMipmaps(tileSpec, firstEntry, sourceImageAndMask.hasMask())) {

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

                if (! tileSpec.hasMipmap(mipmapLevel)) {

                    createMissingDirectories(derivedImageAndMask.getImageUrl());

                    imageMipmapFile = getFileForUrlString(derivedImageAndMask.getImageUrl());
                    sourceImageProcessor = generateMipmapFile(sourceImageProcessor, imageMipmapFile, 1);

                    if (sourceImageAndMask.hasMask()) {
                        createMissingDirectories(derivedImageAndMask.getMaskUrl());
                        maskMipmapFile = getFileForUrlString(derivedImageAndMask.getMaskUrl());
                        sourceMaskProcessor = generateMipmapFile(sourceMaskProcessor, maskMipmapFile, 1);
                    }

                }
            }

        } else {
            LOG.info("generateMissingMipmapFiles: all mipmap files exist for tileId {}",
                     tileSpec.getTileId());
        }
    }

    private boolean isMissingMipmaps(final TileSpec tileSpec,
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

            if (! tileSpec.hasMipmap(mipmapLevel)) {

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
                                              final int mipmapLevelDelta)
            throws IOException {

        final ImageProcessor downSampledProcessor = Downsampler.downsampleImageProcessor(sourceProcessor,
                                                                                         mipmapLevelDelta);
        if (params.forceGeneration || (! targetMipmapFile.exists())) {
            Utils.saveImage(downSampledProcessor.getBufferedImage(),
                            targetMipmapFile.getAbsolutePath(),
                            params.format,
                            false,
                            0.85f);
        }

        return downSampledProcessor;
    }

    public static ImageProcessor loadImageProcessor(final String url)
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
