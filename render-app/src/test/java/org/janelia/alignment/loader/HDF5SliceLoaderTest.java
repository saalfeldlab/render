package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

/**
 * Tests the {@link HDF5SliceLoader} class.
 *
 * @author Eric Trautman
 */
public class HDF5SliceLoaderTest {

    public static void main(final String[] args) {
        final HDF5SliceLoader loader = new HDF5SliceLoader();
        final ImageProcessor ip = loader.load("file:///nrs/cellmap/data/aic_desmosome-2/raw/Gemini450-0113/2021/01/27/02/Gemini450-0113_21-01-27_025406.raw-archive.h5?dataSet=/0-0-0/c0");
        System.out.println(ip);
    }
}
