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

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link Render} class.
 *
 * @author Eric Trautman
 */
public class RenderTest {

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

        Render.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    @Test
    public void testMultichannelStitching() throws Exception {

        final String[] args = {
                "--tile_spec_url", "src/test/resources/multichannel-test/test_2_channels.json",
                "--out", outputFile.getAbsolutePath(),
                "--x", "650",
                "--y", "1600",
                "--width", "4000",
                "--height", "2200",
                "--scale", "0.25"
        };

        final String channel1Name = "DAPI";
        final String channel2Name = "TdTomato";

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
        params.validate();

        final BufferedImage targetImage = params.openTargetImage();
        final int targetWidth = targetImage.getWidth();
        final int targetHeight = targetImage.getHeight();

        final Map<String, ImageProcessorWithMasks> worldTargetChannels = new HashMap<>();

        worldTargetChannels.put(channel1Name, new ImageProcessorWithMasks(new FloatProcessor(targetWidth, targetHeight),
                                                                          new ByteProcessor(targetWidth, targetHeight),
                                                                          null));
        worldTargetChannels.put(channel2Name, new ImageProcessorWithMasks(new FloatProcessor(targetWidth, targetHeight),
                                                                          new ByteProcessor(targetWidth, targetHeight),
                                                                          null));

        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache();

        Render.render(params.getTileSpecs(),
                      worldTargetChannels,
                      params.getX(),
                      params.getY(),
                      params.getRes(params.getScale()),
                      params.getScale(),
                      params.isAreaOffset(),
                      params.getNumberOfThreads(),
                      params.skipInterpolation(),
                      params.doFilter(),
                      params.binaryMask(),
                      params.excludeMask(),
                      imageProcessorCache);

        final ImageProcessorWithMasks channel1 = worldTargetChannels.get(channel1Name);
        channel1.ip.setMinAndMax(100, 6000);

        final BufferedImage channel1Image = Utils.toARGBImage(channel1.ip);

        final ImageProcessorWithMasks channel2 = worldTargetChannels.get(channel2Name);
        channel2.ip.setMinAndMax(0, 10000);

        final BufferedImage channel2Image = Utils.toARGBImage(channel2.ip);

        Assert.assertNotNull("null channel 1 image", channel1Image);
        Assert.assertNotNull("null channel 2 image", channel2Image);
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

        Render.renderUsingCommandLineArguments(args);

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

        Render.renderUsingCommandLineArguments(args);

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

        Render.renderUsingCommandLineArguments(args);

        Assert.assertTrue("rendered file " + outputFile.getAbsolutePath() + " not created",
                          outputFile.exists());

    }

    private void validateCacheRender(final String context,
                                     final RenderParameters params,
                                     final ImageProcessorCache imageProcessorCache,
                                     final int expectedCacheSize,
                                     final int expectedHitCount,
                                     final String expectedDigestString)
            throws Exception {

        final BufferedImage targetImage = params.openTargetImage();

        Render.render(params, targetImage, imageProcessorCache);

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

    private String getDigestString(final File file) throws Exception {
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

    private void deleteTestFile(final File file) {
        if ((file != null) && file.exists()) {
            if (file.delete()) {
                LOG.info("deleteTestFile: deleted " + file.getAbsolutePath());
            } else {
                LOG.info("deleteTestFile: failed to delete " + file.getAbsolutePath());
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTest.class);
}
