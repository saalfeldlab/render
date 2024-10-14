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

        final String basePath = "/nrs/cellmap/data/jrc_mus-cerebellum-3/align";
        final String[] relPaths = {
                "/Merlin-6262/2024/08/14/12/Merlin-6262_24-08-14_120147.uint8.h5",
                "/Merlin-6262/2024/08/14/15/Merlin-6262_24-08-14_155536.uint8.h5"
        };
        final String dataSet = "?dataSet=/0-1-3/mipmap.0&z=0";

        for (final String relPath : relPaths) {
            System.out.println("testing " + relPath);
            final String urlString = "file://" + basePath + relPath + dataSet;
            final ImageProcessor ip = loader.load(urlString);
            System.out.println(ip);
        }
    }
}
