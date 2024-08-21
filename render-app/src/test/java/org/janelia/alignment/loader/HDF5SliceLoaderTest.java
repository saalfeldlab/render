package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link HDF5SliceLoader} class.
 *
 * @author Eric Trautman
 */
public class HDF5SliceLoaderTest {

    @Test
    public void testLoad() {
        final HDF5SliceLoader loader = new HDF5SliceLoader();
        final String urlString = "src/test/resources/hdf5-test/Merlin-6284_24-02-27_180604.uint8.h5?dataSet=/0-0-0/mipmap.0&z=0";
        ImageProcessor ip = null;
        try {
            ip = loader.load(urlString);
        } catch (final Throwable t) {
            final String failureMessage = ExceptionUtils.getStackTrace(t);
            Assert.fail("failed load with following exception: " + failureMessage);
        }
        Assert.assertEquals("invalid width", 5000, ip.getWidth());
    }

    public static void main(final String[] args) {
        final HDF5SliceLoader loader = new HDF5SliceLoader();
        final ImageProcessor ip = loader.load("file:///nrs/cellmap/data/aic_desmosome-2/raw/Gemini450-0113/2021/01/27/02/Gemini450-0113_21-01-27_025406.raw-archive.h5?dataSet=/0-0-0/c0");
        System.out.println(ip);
    }
}
