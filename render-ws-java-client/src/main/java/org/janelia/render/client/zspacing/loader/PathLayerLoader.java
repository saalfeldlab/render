package org.janelia.render.client.zspacing.loader;

import java.io.Serializable;
import java.util.List;

import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 * Loads layer image data from an image file on disk.
 *
 * @author Eric Trautman
 */
public class PathLayerLoader implements LayerLoader, Serializable {

    private final List<String> layerPathList;

    public PathLayerLoader(final List<String> layerPathList) {
        this.layerPathList = layerPathList;
    }

    @Override
    public int getNumberOfLayers() {
        return layerPathList.size();
    }

    @Override
    public FloatProcessors getProcessors(final int layerIndex) {
        final String layerPath = layerPathList.get(layerIndex);
        final ImageProcessor image = new ImagePlus(layerPath).getProcessor();
        return new FloatProcessors(image, null);
    }

}
