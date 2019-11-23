package org.janelia.render.client.cache;

import ij.process.ImageProcessor;

import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor.FeaturesWithSourceData;

/**
 * Cache container for a canvas' list of features.
 *
 * @author Eric Trautman
 */
public class CachedCanvasFeatures implements CachedCanvasData {

    private final FeaturesWithSourceData featuresWithSourceData;
    private final double[] clipOffsets;

    CachedCanvasFeatures(final FeaturesWithSourceData featuresWithSourceData,
                         final double[] clipOffsets) {
        this.featuresWithSourceData = featuresWithSourceData;
        this.clipOffsets = clipOffsets;
    }

    CachedCanvasFeatures(final List<Feature> featureList,
                         final double[] clipOffsets) {
        this(new FeaturesWithSourceData(null,
                                        null,
                                        featureList),
             clipOffsets);
    }

    public List<Feature> getFeatureList() {
        return featuresWithSourceData.getFeatureList();
    }

    public double[] getClipOffsets() {
        return clipOffsets;
    }

    public RenderParameters getRenderParameters() {
        return featuresWithSourceData.getRenderParameters();
    }

    public int getImageProcessorWidth() {
        int width = 0;
        final ImageProcessorWithMasks renderedProcessors = featuresWithSourceData.getRenderedProcessorWithMasks();
        if ((renderedProcessors != null) && (renderedProcessors.ip != null)) {
            width = renderedProcessors.ip.getWidth();
        }
        return width;
    }

    public int getImageProcessorHeight() {
        int height = 0;
        final ImageProcessorWithMasks renderedProcessors = featuresWithSourceData.getRenderedProcessorWithMasks();
        if ((renderedProcessors != null) && (renderedProcessors.ip != null)) {
            height = renderedProcessors.ip.getHeight();
        }
        return height;
    }

    public ImageProcessor getMaskProcessor() {
        ImageProcessor maskProcessor = null;
        final ImageProcessorWithMasks renderedProcessors = featuresWithSourceData.getRenderedProcessorWithMasks();
        if (renderedProcessors != null) {
            maskProcessor = renderedProcessors.mask;
        }
        return maskProcessor;
    }

    public long getKilobytes() {
        return featuresWithSourceData.getKilobytes();
    }

    @Override
    public String toString() {
        return "featureList[" + getFeatureList().size() + "]";
    }

    /** Since feature lists are only in-memory, this method is a no-op. */
    public void remove() {
    }

}