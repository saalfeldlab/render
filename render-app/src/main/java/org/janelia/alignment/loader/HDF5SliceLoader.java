package org.janelia.alignment.loader;

import java.io.IOException;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;

/**
 * Loads a 2D slice of an hdf5 volume identified as:
 * <pre>
 *     h5://[basePath]?dataSet=[dataSet]&z=[z]
 *
 *     Example:
 *       h5:///nrs/flyem/tmp/VNC-align.n5?dataSet=/align/slab-26/raw/s0&x=512&y=640&z=1656&w=384&h=640
 * </pre>
 *
 * @author Eric Trautman
 */
public class HDF5SliceLoader extends N5SliceLoader {

    /** Shareable instance of this loader. */
    public static final HDF5SliceLoader INSTANCE = new HDF5SliceLoader();

    @Override
    public N5Reader buildReader(final String basePath)
            throws IOException {
        return new N5HDF5Reader(basePath);
    }
}
