package org.janelia.alignment.loader;

/**
 * Common elements for loaders of 3D sources that can be indexed by a slice number.
 *
 * @author Eric Trautman
 */
public abstract class SliceLoader implements ImageLoader {

    private final int sliceNumber;

    public SliceLoader(final int sliceNumber) {
        this.sliceNumber = sliceNumber;
    }

    public int getSliceNumber() {
        return sliceNumber;
    }

    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return (otherLoader instanceof SliceLoader) && (this.sliceNumber == ((SliceLoader) otherLoader).sliceNumber);
    }

}
