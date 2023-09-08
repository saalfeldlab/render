package org.janelia.render.client.zspacing.loader;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.util.ImageProcessorCache;

/**
 * Parameters for masking resin areas from cross correlation calculations.
 *
 * @author Eric Trautman
 */
@Parameters
public class ResinMaskParameters
        implements Serializable {

    @Parameter(
            names = "--resinMaskingEnabled",
            description = "Specify as 'false' to skip masking of resin areas",
            arity = 1)
    public boolean resinMaskingEnabled = true;

    @Parameter(
            names = "--resinSigma",
            description = "Standard deviation for gaussian convolution")
    public Integer resinSigma = 100;

    @Parameter(
            names = "--resinContentThreshold",
            description = "Threshold intensity that identifies content")
    public Double resinContentThreshold = 3.0;

    @Parameter(
            names = "--resinMaskIntensity",
            description = "Intensity value to use when masking resin areas (typically max intensity for image)")
    public Float resinMaskIntensity = 255.0f;

    public ResinMaskParameters() {
    }

    public RenderLayerLoader buildLoader(final String layerUrlPattern,
                                         final List<Double> sortedZList,
                                         final ImageProcessorCache imageProcessorCache,
                                         final double renderScale) {
        final RenderLayerLoader layerLoader;
        if (resinMaskingEnabled)  {
            layerLoader = new MaskedResinLayerLoader(layerUrlPattern,
                                                     sortedZList,
                                                     imageProcessorCache,
                                                     resinSigma,
                                                     renderScale,
                                                     resinContentThreshold,
                                                     resinMaskIntensity);
        } else {
            layerLoader = new RenderLayerLoader(layerUrlPattern,
                                                sortedZList,
                                                imageProcessorCache);
        }
        return layerLoader;
    }
}