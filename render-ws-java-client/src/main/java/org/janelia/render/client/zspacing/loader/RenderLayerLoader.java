package org.janelia.render.client.zspacing.loader;

import java.io.File;
import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.util.ImageProcessorCache;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Loads layer image data from render web service.
 *
 * @author Eric Trautman
 */
public class RenderLayerLoader implements LayerLoader, Serializable {

    private final String layerUrlPattern;
    private final List<Double> sortedZList;
    private final ImageProcessorCache imageProcessorCache;

    private String debugFilePattern;

    /**
     * @param layerUrlPattern      render parameters URL pattern for each layer to be loaded
     *                             that includes one '%s' element for z substitution
     *                             (e.g. http://[base-url]/owner/o/project/p/stack/s/z/%s/box/0,0,2000,2000,0.125/render-parameters).
     * @param sortedZList          sorted list of z values for the layers to be loaded.
     * @param imageProcessorCache  source data cache (only useful for caching source masks).
     *
     * @throws IllegalArgumentException
     *   if an invalid layer URL pattern is specified.
     */
    public RenderLayerLoader(final String layerUrlPattern,
                             final List<Double> sortedZList,
                             final ImageProcessorCache imageProcessorCache)
            throws IllegalArgumentException {

        validatePattern(layerUrlPattern);

        this.layerUrlPattern = layerUrlPattern;
        this.sortedZList = sortedZList;
        this.debugFilePattern = null;
        this.imageProcessorCache = imageProcessorCache;
    }

    @Override
    public int getNumberOfLayers() {
        return sortedZList.size();
    }

    @Override
    public FloatProcessors getProcessors(final int layerIndex) {
        final Double z = sortedZList.get(layerIndex);
        final String url = String.format(layerUrlPattern, z);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);

        final File debugFile = debugFilePattern == null ? null : new File(String.format(debugFilePattern, z));

        final ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters,
                                                       imageProcessorCache,
                                                       debugFile);

        return new FloatProcessors(imageProcessorWithMasks.ip, imageProcessorWithMasks.mask, renderParameters);
    }

    /**
     * @param debugFilePattern  file path pattern for soring rendered debug images that includes
     *                          one '%s' element for z substitution (e.g. /tmp/debug/%s.png).
     *                          Set to null to disable creation of debug images.
     *
     * @throws IllegalArgumentException
     *   if an invalid debug file pattern is specified.
     */
    public void setDebugFilePattern(final String debugFilePattern)
            throws IllegalArgumentException {

        if (debugFilePattern != null) {
            validatePattern(debugFilePattern);
        }

        this.debugFilePattern = debugFilePattern;
    }

    private void validatePattern(final String pattern)
            throws IllegalArgumentException {

        final int firstTokenIndex = pattern.indexOf('%');
        if ((firstTokenIndex == -1) || (pattern.indexOf('%', firstTokenIndex + 2) > -1)) {
            throw new IllegalArgumentException("pattern '" + pattern +
                                               "' must contain one and only one '%' token for the layer z value");
        }
    }
}
