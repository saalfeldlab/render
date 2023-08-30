package org.janelia.alignment;

import ij.process.ShortProcessor;

import java.io.Serializable;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders 16-bit bounding boxes from tiles stored in render.
 */
public class ShortBoxRenderer
        implements Serializable {

    private final String stackUrl;
    private final String boxUrlSuffix;
    private final Double minIntensity;
    private final Double maxIntensity;
    private final boolean exportMaskOnly;

    public ShortBoxRenderer(final String baseUrl,
                            final String owner,
                            final String project,
                            final String stack,
                            final long width,
                            final long height,
                            final double scale,
                            final Double minIntensity,
                            final Double maxIntensity,
                            final boolean exportMaskOnly) {
        this.stackUrl = String.format("%s/owner/%s/project/%s/stack/%s", baseUrl, owner, project, stack);
        this.boxUrlSuffix = String.format("%d,%d,%f/render-parameters", width, height, scale);
        this.minIntensity = minIntensity;
        this.maxIntensity = maxIntensity;
        this.exportMaskOnly = exportMaskOnly;
    }

    public ShortProcessor render(final long x,
                                 final long y,
                                 final long z,
                                 final ImageProcessorCache ipCache) {
        final String renderParametersUrlString = String.format("%s/z/%d/box/%d,%d,%s",
                                                               stackUrl, z, x, y, boxUrlSuffix);
        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);
        if (minIntensity != null) {
            renderParameters.setMinIntensity(minIntensity);
        }
        if (maxIntensity != null) {
            renderParameters.setMaxIntensity(maxIntensity);
        }

        final ShortProcessor renderedProcessor;
        if (renderParameters.numberOfTileSpecs() > 0) {
            if (exportMaskOnly) {
                for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
                    tileSpec.replaceFirstChannelImageWithMask(true);
                }
            }
            final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                    Renderer.renderImageProcessorWithMasks(renderParameters, ipCache);
            renderedProcessor = ipwm.ip.convertToShortProcessor();
        } else {
            LOG.info("render: no tiles found in {}", renderParametersUrlString);
            final double derivedScale = renderParameters.getScale();
            final int targetWidth = (int) (derivedScale * renderParameters.getWidth());
            final int targetHeight = (int) (derivedScale * renderParameters.getHeight());
            renderedProcessor = new ShortProcessor(targetWidth, targetHeight);
        }

        return renderedProcessor;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ShortBoxRenderer.class);

}
