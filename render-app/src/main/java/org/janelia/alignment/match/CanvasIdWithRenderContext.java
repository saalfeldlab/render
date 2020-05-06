package org.janelia.alignment.match;

import com.google.common.base.Objects;

import java.io.Serializable;

import org.janelia.alignment.RenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates a {@link CanvasId} with its full render context.
 * Original intended use is to support caching of data (e.g. features, peaks) associated
 * with different forms (e.g. scales, filtering) of the same canvas.
 *
 * @author Eric Trautman
 */
public class CanvasIdWithRenderContext
        implements Serializable {

    private final CanvasId canvasId;
    private final String url;
    private final Integer clipWidth;
    private final Integer clipHeight;

    public CanvasIdWithRenderContext(final CanvasId canvasId,
                                     final String url,
                                     final Integer clipWidth,
                                     final Integer clipHeight) {
        this.canvasId = canvasId;
        this.url = url;
        this.clipWidth = clipWidth;
        this.clipHeight = clipHeight;
    }

    public CanvasId getCanvasId() {
        return canvasId;
    }

    public String getUrl() {
        return url;
    }

    public Integer getClipWidth() {
        return clipWidth;
    }

    public Integer getClipHeight() {
        return clipHeight;
    }

    public double[] getClipOffsets() {
        return canvasId.getClipOffsets();
    }

    public RenderParameters loadRenderParameters()
            throws IllegalArgumentException {

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);

        if ((clipWidth != null) || (clipHeight != null)) {
            // TODO: setting the canvas offsets here is hack-y, probably want a cleaner way
            canvasId.setClipOffsets(renderParameters.getWidth(), renderParameters.getHeight(), clipWidth, clipHeight);
            renderParameters.clipForMontagePair(canvasId, clipWidth, clipHeight);

            final double[] offsets = canvasId.getClipOffsets();
            LOG.info("loadRenderParameters: loaded {} with offsets ({}, {})", canvasId, offsets[0], offsets[1]);
        } else {
            LOG.info("loadRenderParameters: loaded {}", canvasId);
        }


        return renderParameters;
    }

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        final CanvasIdWithRenderContext canvasIdWithRenderContext = (CanvasIdWithRenderContext) that;
        return Objects.equal(url, canvasIdWithRenderContext.url) &&
               Objects.equal(clipWidth, canvasIdWithRenderContext.clipWidth) &&
               Objects.equal(clipHeight, canvasIdWithRenderContext.clipHeight);
    }

    @Override
    public int hashCode() {
        // ignore clipWidth and clipHeight for hashCode since those values
        // should rarely (if ever) differ for canvases with the same URL
        return url.hashCode();
    }

    @Override
    public String toString() {
        return "{" +
               "canvasId: '" + canvasId +
               "', url: '" + url + '\'' +
               (clipWidth == null ? "" : ", clipWidth: " + clipWidth) +
               (clipHeight == null ? "" : ", clipHeight: " + clipHeight) +
               '}';
    }


    public static CanvasIdWithRenderContext build(final CanvasId canvasId,
                                                  final CanvasRenderParametersUrlTemplate urlTemplate) {
        return new CanvasIdWithRenderContext(canvasId,
                                             urlTemplate.getRenderParametersUrl(canvasId),
                                             urlTemplate.getClipWidth(),
                                             urlTemplate.getClipHeight());
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasIdWithRenderContext.class);
}
