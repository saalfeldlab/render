package org.janelia.alignment;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility to generate mipmap files.
 *
 * The core generation logic can be accessed through the static {@link MipmapGenerator#generateMipmap} method
 * while the {@link MipmapGenerator#generateMissingMipmapFiles} method can be used to generate missing
 * mipmaps for a specific tile.  Finally, the generator can be run as a stand-alone tool that generates
 * missing mipmaps for a list of tiles (see {@link MipmapGeneratorParameters}.
 *
 * When generating mipmaps for a specific tile, a base path and name is used for all mipmaps that avoids
 * collisions with other generated mipmaps.
 * This base path is created by concatenating the root mipmap directory
 * (e.g. /groups/saalfeld/generated-mipmaps) with the raw (level zero) image (or mask) source path and name
 * (e.g. /groups/saalfeld/raw-data/stack-1/file1.tif).
 *
 * The full path for individual generated mipmaps is created by concatenating the tile's base path with the
 * level and format of the mipmap
 * (e.g. /groups/saalfeld/generated-mipmaps/groups/saalfeld/raw-data/stack-1/file1.tif_level_2_mipmap.jpg).
 *
 * @author Eric Trautman
 */
public class MipmapGenerator {

    public static void main(String[] args) {

        File outputFile = null;
        FileOutputStream outputStream = null;
        long tileCount = 0;
        try {

            final MipmapGeneratorParameters params = MipmapGeneratorParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                params.validate();

                MipmapGenerator mipmapGenerator = new MipmapGenerator(params.getRootDirectory(),
                                                                      params.getFormat(),
                                                                      params.getQuality(),
                                                                      params.consolidateMasks(),
                                                                      params.forceBoxCalculation());

                final int mipmapLevel = params.getMipmapLevel();
                final List<TileSpec> tileSpecs = params.getTileSpecs();
                long timeOfLastProgressLog = System.currentTimeMillis();
                TileSpec updatedTileSpec;
                outputFile = params.getOutputFile();
                outputStream = new FileOutputStream(outputFile);
                outputStream.write("[\n".getBytes());
                for (TileSpec tileSpec : tileSpecs) {
                    updatedTileSpec = mipmapGenerator.generateMissingMipmapFiles(tileSpec, mipmapLevel);
                    if (tileCount != 0) {
                        outputStream.write(",\n".getBytes());
                    }
                    outputStream.write(JsonUtils.GSON.toJson(updatedTileSpec).getBytes());
                    tileCount++;
                    if ((System.currentTimeMillis() - timeOfLastProgressLog) > 10000) {
                        LOG.info("main: updated tile {} of {}", tileCount, tileSpecs.size());
                        timeOfLastProgressLog = System.currentTimeMillis();
                    }
                }
                outputStream.write("\n]".getBytes());

                LOG.info("main: updated {} tile specs and saved to {}", tileCount, outputFile);
            }


        } catch (Throwable t) {
            LOG.error("main: caught exception", t);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    LOG.warn("main: failed to close " + outputFile + ", ignoring error", e);
                }

            }
        }
    }

    private File rootDirectory;
    private String format;
    private float jpegQuality;
    private boolean consolidateMasks;
    private boolean forceBoxCalculation;
    private MessageDigest messageDigest;
    private Map<String, File> sourceDigestToMaskMipmapBaseFileMap;

    /**
     * Constructs a generator for use with a specific base path.
     *
     * @param  rootDirectory     the root directory for all generated mipmap files.
     * @param  format            the format for all generated mipmap files.
     * @param  jpegQuality       the jpg quality factor (0.0 to 1.0) which is only used when generating jpg mipmaps.
     * @param  consolidateMasks  if true, consolidate equivalent zipped TrakEM2 mask files.
     * @param  forceBoxCalculation  if true, recalculate tile bounding box attributes even if they already exist.
     */
    public MipmapGenerator(File rootDirectory,
                           String format,
                           float jpegQuality,
                           boolean consolidateMasks,
                           boolean forceBoxCalculation) {
        this.rootDirectory = rootDirectory;
        this.format = format;
        this.jpegQuality = jpegQuality;
        this.consolidateMasks = consolidateMasks;
        this.forceBoxCalculation = forceBoxCalculation;

        if (consolidateMasks) {
            try {
                messageDigest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("failed to create MD5 message digest for TrakEM2 mask files", e);
            }
            this.sourceDigestToMaskMipmapBaseFileMap = new HashMap<String, File>();
        }
    }

    /**
     * Examines the specified tile specification and generates any missing image and/or mask mipmaps
     * for all levels less than or equal to the specified greatest level.
     *
     * @param  tileSpec             the source tile specification which must include at least a
     *                              level zero image mipmap.
     *
     * @param  greatestMipmapLevel  the level scaling threshold.
     *
     * @return the tile specification updated with all newly generated mipmap path information.
     *
     * @throws IllegalArgumentException
     *   if a level zero image mipmap is missing from the tile specification.
     *
     * @throws IOException
     *   if mipmap files cannot be generated for any reason.
     */
    public TileSpec generateMissingMipmapFiles(TileSpec tileSpec,
                                               int greatestMipmapLevel)
            throws IllegalArgumentException, IOException {

        ImageAndMask imageAndMask = tileSpec.getMipmap(0);

        if ((imageAndMask == null) || (! imageAndMask.hasImage())) {
            throw new IllegalArgumentException("level 0 mipmap is missing from " + tileSpec);
        }

        final File imageMipmapBaseFile = getMipmapBaseFile(imageAndMask.getImageUrl(), true);

        File maskMipmapBaseFile = null;
        final boolean hasMask = imageAndMask.hasMask();
        if (hasMask) {

            maskMipmapBaseFile = getMipmapBaseFile(imageAndMask.getMaskUrl(), true);

            if (consolidateMasks) {
                final File sourceMaskFile = getFileForUrlString(imageAndMask.getMaskUrl());
                final String sourceDigest = getDigest(sourceMaskFile);
                if (sourceDigestToMaskMipmapBaseFileMap.containsKey(sourceDigest)) {
                    maskMipmapBaseFile = sourceDigestToMaskMipmapBaseFileMap.get(sourceDigest);
                } else {
                    sourceDigestToMaskMipmapBaseFileMap.put(sourceDigest, maskMipmapBaseFile);
                }
            }

        }

        File imageMipmapFile;
        File maskMipmapFile;
        for (int mipmapLevel = 1; mipmapLevel <= greatestMipmapLevel; mipmapLevel++) {
            if (! tileSpec.hasMipmap(mipmapLevel)) {
                imageMipmapFile = getMipmapFile(imageMipmapBaseFile, mipmapLevel);
                generateMipmapFile(imageAndMask.getImageUrl(), imageMipmapFile, 1);

                if (hasMask) {
                    maskMipmapFile = getMipmapFile(maskMipmapBaseFile, mipmapLevel);
                    generateMipmapFile(imageAndMask.getMaskUrl(), maskMipmapFile, 1);
                } else {
                    maskMipmapFile = null;
                }

                imageAndMask = new ImageAndMask(imageMipmapFile, maskMipmapFile);
                tileSpec.putMipmap(mipmapLevel, imageAndMask);

            } else {
                imageAndMask = tileSpec.getMipmap(mipmapLevel);
            }
        }

        tileSpec = Render.deriveBoundingBox(tileSpec, forceBoxCalculation);

        return tileSpec;
    }

    /**
     * Creates the base path for all level mipmaps generated for the specified source.
     * This path is a concatenation of the base mipmap storage path followed by the full source path.
     * For example, a base path might look like this:
     *   /groups/saalfeld/generated-mipmaps/groups/saalfeld/raw-data/stack-1/file1.tif
     *
     * If the createMissingDirectories parameter is true, then any missing directories in the base path
     * will be created.
     *
     * This method is marked as protected instead of private to facilitate unit testing.
     *
     * @param  levelZeroSourceUrl        source path for the level zero image or mask.
     * @param  createMissingDirectories  indicates whether non-existent directories in the path should be created.
     *
     * @return the base path file.
     *
     * @throws IllegalArgumentException
     *   if the source path cannot be parsed.
     *
     * @throws IOException
     *   if missing directories cannot be created.
     */
    protected File getMipmapBaseFile(String levelZeroSourceUrl,
                                     boolean createMissingDirectories)
            throws IllegalArgumentException, IOException {
        final File sourceFile = getFileForUrlString(levelZeroSourceUrl);
        final File sourceDirectory = sourceFile.getParentFile().getCanonicalFile();
        final File mipmapBaseDirectory = new File(rootDirectory, sourceDirectory.getAbsolutePath()).getCanonicalFile();
        if (! mipmapBaseDirectory.exists()) {
            if (createMissingDirectories) {
                if (!mipmapBaseDirectory.mkdirs()) {
                    throw new IllegalArgumentException("failed to create mipmap level directory " +
                                                       mipmapBaseDirectory.getAbsolutePath());
                }
            }
        }
        return new File(mipmapBaseDirectory, sourceFile.getName());
    }

    private File getFileForUrlString(String url) {
        final URI uri = Utils.convertPathOrUriStringToUri(url);
        return new File(uri);
    }

    private File getMipmapFile(File mipmapBaseFile,
                               int mipmapLevel) {
        return new File(mipmapBaseFile.getAbsolutePath() + "_level_" + mipmapLevel + "_mipmap." + format);
    }

    private void generateMipmapFile(String sourceUrl,
                                    File targetMipmapFile,
                                    int mipmapLevelDelta)
            throws IOException {

        if (! targetMipmapFile.exists()) {
            FileOutputStream outputStream = null;
            try {
                outputStream = new FileOutputStream(targetMipmapFile);
                generateMipmap(sourceUrl, mipmapLevelDelta, format, jpegQuality, outputStream);
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (Throwable t) {
                        LOG.warn("failed to close " + targetMipmapFile.getAbsolutePath() + " (ignoring error)", t);
                    }
                }
            }
        }
    }

    private String getDigest(File file)
            throws IOException {

        messageDigest.reset();

        ZipFile zipFile = null;
        InputStream inputStream = null;
        try {

            if (file.getAbsolutePath().endsWith(".zip")) {
                zipFile = new ZipFile(file);
                final Enumeration<? extends ZipEntry> e = zipFile.entries();
                if (e.hasMoreElements()) {
                    final ZipEntry zipEntry = e.nextElement();
                    // only use unzipped input stream if the zipped file contains a single mask
                    if (! e.hasMoreElements()) {
                        inputStream = zipFile.getInputStream(zipEntry);
                    }
                }
            }

            if (inputStream == null) {
                inputStream = new FileInputStream(file);
            }

            final byte[] bytes = new byte[2048];
            int numBytes;
            while ((numBytes = inputStream.read(bytes)) != -1) {
                messageDigest.update(bytes, 0, numBytes);
            }

        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOG.warn("failed to close " + file.getAbsolutePath() + ", ignoring error", e);
                }
            }
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    LOG.warn("failed to close zip file " + file.getAbsolutePath() + ", ignoring error", e);
                }
            }
        }

        final byte[] digest = messageDigest.digest();

        // create string representation of digest that matches output generated by tools like md5sum
        BigInteger bigInt = new BigInteger(1, digest);
        return bigInt.toString(16);
    }

    /**
     * Generates a mipmap and writes it to the specified output stream.
     *
     * @param  sourceUrl         URL string for the source image to be down-sampled.
     *
     * @param  mipmapLevelDelta  difference between the mipmap level of the source image and
     *                           the mipmap level for the generated image (should be positive).
     *
     * @param  format            format of the down-sampled image (e.g. {@link Utils#JPEG_FORMAT})
     *
     * @param  jpegQuality       JPEG quality (0.0 <= x <= 1.0, only relevant for JPEG images).
     *
     * @param  outputStream      output stream for the down-sampled image.
     *
     * @throws IllegalArgumentException
     *   if the source URL cannot be loaded or an invalid delta value is specified.
     *
     * @throws IOException
     *   if the down-sampled image cannot be written.
     */
    public static void generateMipmap(String sourceUrl,
                                      int mipmapLevelDelta,
                                      String format,
                                      Float jpegQuality,
                                      OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        final ImagePlus sourceImagePlus = Utils.openImagePlusUrl(sourceUrl);
        if (sourceImagePlus == null) {
            throw new IllegalArgumentException("failed to load '" + sourceUrl + "' for scaling");
        }

        if (mipmapLevelDelta < 1) {
            throw new IllegalArgumentException("mipmap level delta value (" + mipmapLevelDelta +
                                               ") must be greater than 0");
        }

        final ImageProcessor downSampledProcessor =
                Downsampler.downsampleImageProcessor(sourceImagePlus.getProcessor(),
                                                     mipmapLevelDelta);
        final BufferedImage downSampledImage = downSampledProcessor.getBufferedImage();
        final ImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(outputStream);

        Utils.writeImage(downSampledImage, format, jpegQuality, imageOutputStream);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapGenerator.class);

}
