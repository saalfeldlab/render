package org.janelia.alignment;

import com.google.common.cache.CacheStats;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link ArgbRenderer} class.
 *
 * @author Eric Trautman
 */
public class ArgbRendererTest {

    private String modulePath;
    private File outputFile;

    @Before
    public void setup() throws Exception {
        final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = TIMESTAMP.format(new Date());
        outputFile = new File("test-render-" + timestamp +".jpg").getCanonicalFile();
        modulePath = outputFile.getParentFile().getCanonicalPath();
    }

    @After
    public void tearDown() {
        deleteTestFile(outputFile);
    }

    @Test
    public void testStitching() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles.jpg");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

//        final String[] args = {
//                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles.json",
//                "--out", outputFile.getAbsolutePath(),
//                "--x", "1800",
//                "--y", "1850",
//                "--width", "100",
//                "--height", "20",
//                "--scale", "0.1"
//        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

//    @Test
//    public void testBinaryMaskStitching() throws Exception {
//
//        // use png to avoid jpg compression artifacts when inspecting binary result
//        final String pngFileName = outputFile.getName().replace(".jpg", ".png");
//        outputFile = new File(outputFile.getParentFile(), pngFileName);
//
//        final String[] args = {
//                "--tile_spec_url", "src/test/resources/stitch-test/200x200-rotated-stitch.json",
//                "--out", outputFile.getAbsolutePath(),
//                "--width", "400",
//                "--height", "400",
//                "--binary_mask", // comment this line out to see interpolated version of rotated tile borders
//                "--scale", "1.0"
//        };
//
//        ArgbRenderer.renderUsingCommandLineArguments(args);
//
//        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created",
//                          outputFile.exists());
//    }

    @Test
    public void testMixedMaskStitching() throws Exception {

        // TODO: understand why v0.3 result differs slightly (at right edge) from v2.0 result
        //       see expected_stitched_4_tiles_with_mixed_masks_v0.3.jpg for comparison

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles_with_mixed_masks.jpg");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles_with_mixed_masks.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    @Test
    public void testMultichannelStitching() throws Exception {

        File expectedFile = new File(modulePath + "/src/test/resources/multichannel-test/expected_dapi_1_0.jpg");

        String[] args = {
                "--tile_spec_url", "src/test/resources/multichannel-test/test_2_channels.json",
                "--channels", "DAPI",
                "--out", outputFile.getAbsolutePath(),
                "--x", "650",
                "--y", "1600",
                "--width", "4000",
                "--height", "2200",
                "--scale", "0.25"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        String expectedDigestString = getDigestString(expectedFile);
        String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("for DAPI, stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);

        expectedFile = new File(modulePath + "/src/test/resources/multichannel-test/expected_dapi_0_9_td_0_1.jpg");

        args = new String[] {
                "--tile_spec_url", "src/test/resources/multichannel-test/test_2_channels.json",
                "--channels", "DAPI__0.9__TdTomato__0.1",
                "--out", outputFile.getAbsolutePath(),
                "--x", "650",
                "--y", "1600",
                "--width", "4000",
                "--height", "2200",
                "--scale", "0.25"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        expectedDigestString = getDigestString(expectedFile);
        actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("for DAPI__0.9__TdTomato_0.1, stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);

    }

    @Test
    public void testStitchingWithLevel1Masks() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles.jpg");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles_level_1.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    @Test
    public void testComposite() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/composite-test/expected_composite.jpg");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/composite-test/test_composite.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "846",
                "--height", "842",
                "--scale", "0.2"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("composite file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    @Test
    public void testMaskMipmap() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/mipmap-test/mask_mipmap_expected_result.jpg");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/mipmap-test/mask_mipmap_test.json",
                "--out", outputFile.getAbsolutePath(),
                "--x", "1000",
                "--y", "3000",
                "--width", "1000",
                "--height", "1000",
                "--scale", "0.125"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("rendered file " + outputFile.getAbsolutePath() + " not created",
                          outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("rendered file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    @Test
    public void testUpScaledImage() throws Exception {

        final String[] args = {
                "--tile_spec_url", "src/test/resources/mipmap-test/upscale_test.json",
                "--out", outputFile.getAbsolutePath(),
                "--x", "0",
                "--y", "0",
                "--width", "2560",
                "--height", "2160",
                "--scale", "1.0"
        };

        try {
            ArgbRenderer.renderUsingCommandLineArguments(args);
            Assert.fail("attempt to render upscale image should throw exception");
        } catch (final IllegalArgumentException iae) {
            Assert.assertTrue("invalid exception message",
                              iae.getMessage().startsWith("The highest resolution mipmap"));
        }

    }

    @Test
    public void testCaching() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles.jpg");
        final String expectedDigestString = getDigestString(expectedFile);

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles_level_1.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS, true, false);

        validateCacheRender("first run with cache",
                            params, imageProcessorCache, 5, 3, expectedDigestString);
        validateCacheRender("second run with cache",
                            params, imageProcessorCache, 5, 11, expectedDigestString);
        validateCacheRender("third run with NO cache",
                            params, ImageProcessorCache.DISABLED_CACHE, 0, 0, expectedDigestString);
    }

    @Test
    public void testCachingWithTiffStack() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles.jpg");
        final String expectedDigestString = getDigestString(expectedFile);

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles_tiff_stack.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
                                        true, false);

        validateCacheRender("first run with cache",
                            params, imageProcessorCache, 5, 3, expectedDigestString);
        validateCacheRender("second run with cache",
                            params, imageProcessorCache, 5, 11, expectedDigestString);
        validateCacheRender("third run with NO cache",
                            params, ImageProcessorCache.DISABLED_CACHE, 0, 0, expectedDigestString);
    }

    // NOTE: must have /nrs/flyem mounted for this test to work
//    @Test
//    public void testCachingWithN5Slice() throws Exception {
//
//        //
//        final String[] args = {
//                "--tile_spec_url", "src/test/resources/stitch-test/test_n5.json",
//                // "--tile_spec_url", "src/test/resources/stitch-test/test_n5_big.json",   // change expected cache counts
//                // "--tile_spec_url", "src/test/resources/stitch-test/test_n5_small.json", // change expected cache counts
//                "--out", outputFile.getAbsolutePath(),
//                "--x", "512",
//                "--y", "640",
//                "--width", "384",
//                "--height", "640",
//                "--scale", "1.0"
//        };
//
//        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
//        final ImageProcessorCache imageProcessorCache =
//                new ImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
//                                        true, false);
//
//        validateCacheRender("first run with cache",
//                            params, imageProcessorCache, 2, 0, null);
//        validateCacheRender("second run with cache",
//                            params, imageProcessorCache, 2, 2, null);
//        validateCacheRender("third run with NO cache",
//                            params, ImageProcessorCache.DISABLED_CACHE, 0, 0, null);
//    }

    // NOTE: must have h5 data available and need to un-shelve Fibsem8Bit filter code for this to work
//    @Test
//    public void testCachingWithHDF5Slice() throws Exception {
//
//        //
//        final String[] args = {
//                "--tile_spec_url", "src/test/resources/stitch-test/test_hdf5.json",
//                "--out", outputFile.getAbsolutePath(),
//                "--x", "0",
//                "--y", "0",
//                "--width", "15000",
//                "--height", "4200",
//                "--scale", "0.1"
//        };
//
//        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
//        params.setFilterSpecs(Collections.singletonList(new FilterSpec("org.janelia.alignment.filter.Fibsem8Bit",
//                                                                       new HashMap<>())));
//        final ImageProcessorCache imageProcessorCache =
//                new ImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
//                                        true, false);
//
//        final TransformMeshMappingWithMasks.ImageProcessorWithMasks result =
//                Renderer.renderImageProcessorWithMasks(params, imageProcessorCache);
//
//        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
//        final File debugFile = new File("/Users/trautmane/Desktop/test.lou." + sdf.format(new Date()) + ".png");
//        final BufferedImage image = result.ip.getBufferedImage();
//        Utils.saveImage(image, debugFile, false, 0.85f);
//    }

    @Test
    public void testSuperDownSample() throws Exception {

        final String[] args = {
                "--tile_spec_url", "src/test/resources/mipmap-test/mask_mipmap_test.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "10000",
                "--height", "10000",
                "--scale", "0.0001"
        };

        ArgbRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("rendered file " + outputFile.getAbsolutePath() + " not created",
                          outputFile.exists());

    }

    @Test
    public void testExcludeMask() {

        final String imageUrl = "src/test/resources/stitch-test/col0075_row0021_cam1.png";
        final String maskUrl = "src/test/resources/stitch-test/test_mask.jpg";
        final int fullScaleWidth = 2650;
        final int fullScaleHeight = 2250;
        final double scale = 0.02;

        final RenderParameters parametersWithoutMask =
                getParametersForTile(imageUrl, null, fullScaleWidth, fullScaleHeight, scale);
        final BufferedImage expectedRenderedImage = parametersWithoutMask.openTargetImage();
        ArgbRenderer.render(parametersWithoutMask, expectedRenderedImage, ImageProcessorCache.DISABLED_CACHE);

        final RenderParameters parametersWithMask =
                getParametersForTile(imageUrl, maskUrl, fullScaleWidth, fullScaleHeight, scale);
        parametersWithMask.setExcludeMask(true);
        final BufferedImage actualRenderedImage = parametersWithMask.openTargetImage();
        ArgbRenderer.render(parametersWithMask, actualRenderedImage, ImageProcessorCache.DISABLED_CACHE);

        final int width = expectedRenderedImage.getWidth();
        final int height = expectedRenderedImage.getHeight();
        Assert.assertEquals("widths do not match", width, actualRenderedImage.getWidth());
        Assert.assertEquals("height do not match", height, actualRenderedImage.getHeight());

        boolean hasAtLeastOneNonZeroPixel = false;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                final int expectedPixel = expectedRenderedImage.getRGB(x, y);
                final int actualPixel = actualRenderedImage.getRGB(x, y);
                if (expectedPixel != actualPixel) {
                    Assert.assertEquals("pixel at (" + x + ", " + y + ") does not match", expectedPixel, actualPixel);
                }
                if (actualPixel != 0) {
                    hasAtLeastOneNonZeroPixel = true;
                }
            }
        }

        Assert.assertTrue("image is empty", hasAtLeastOneNonZeroPixel);
    }

    @SuppressWarnings("SameParameterValue")
    private RenderParameters getParametersForTile(final String imageUrl,
                                                  final String maskUrl,
                                                  final int fullScaleWidth,
                                                  final int fullScaleHeight,
                                                  final double scale) {

        final ImageAndMask imageAndMask = new ImageAndMask(imageUrl,maskUrl);

        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, imageAndMask);

        final TileSpec tileSpec = new TileSpec();
        tileSpec.addChannel(channelSpec);

        final RenderParameters parameters = new RenderParameters(null,
                                                                 0,
                                                                 0,
                                                                 fullScaleWidth,
                                                                 fullScaleHeight,
                                                                 scale);
        parameters.addTileSpec(tileSpec);
        parameters.initializeDerivedValues();

        return parameters;
    }

    private void validateCacheRender(final String context,
                                     final RenderParameters params,
                                     final ImageProcessorCache imageProcessorCache,
                                     final int expectedCacheSize,
                                     final int expectedHitCount,
                                     final String expectedDigestString)
            throws Exception {

        final BufferedImage targetImage = params.openTargetImage();

        ArgbRenderer.render(params, targetImage, imageProcessorCache);

        Assert.assertEquals(context + ": invalid number of items in cache",
                            expectedCacheSize, imageProcessorCache.size());

        final CacheStats stats = imageProcessorCache.getStats();

        Assert.assertEquals(context + ": invalid number of cache hits",
                            expectedHitCount, stats.hitCount());

        if (expectedDigestString != null) {
            Utils.saveImage(targetImage,
                            outputFile.getAbsolutePath(),
                            Utils.JPEG_FORMAT,
                            params.isConvertToGray(),
                            params.getQuality());

            final String actualDigestString = getDigestString(outputFile);

            Assert.assertEquals(context + ": stitched file MD5 hash differs from expected result",
                                expectedDigestString, actualDigestString);
        }
    }

    static String getDigestString(final File file) throws Exception {
        final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        final FileInputStream fileInputStream = new FileInputStream(file);
        final DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, messageDigest);

        final byte[] buffer = new byte[8192];
        for (int bytesRead = 1; bytesRead > 0;) {
            bytesRead = digestInputStream.read(buffer);
        }

        final byte[] digestValue = messageDigest.digest();
        final StringBuilder sb = new StringBuilder(digestValue.length);
        for (final byte b : digestValue) {
            sb.append(b);
        }

        return sb.toString();
    }

    public static void deleteTestFile(final File file) {
        if ((file != null) && file.exists()) {
            if (file.delete()) {
                LOG.info("deleteTestFile: deleted " + file.getAbsolutePath());
            } else {
                LOG.info("deleteTestFile: failed to delete " + file.getAbsolutePath());
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ArgbRendererTest.class);
}
