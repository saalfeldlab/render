/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
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
    public void tearDown() throws Exception {
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
    public void testExcludeMask() throws Exception {

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

        Utils.saveImage(targetImage,
                        outputFile.getAbsolutePath(),
                        Utils.JPEG_FORMAT,
                        params.isConvertToGray(),
                        params.getQuality());

        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals(context + ": stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    public static String getDigestString(final File file) throws Exception {
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
