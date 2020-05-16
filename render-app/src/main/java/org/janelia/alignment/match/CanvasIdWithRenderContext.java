package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    private final String loaderName;
    private final String url;
    private final Integer clipWidth;
    private final Integer clipHeight;

    public CanvasIdWithRenderContext(@Nonnull final CanvasId canvasId,
                                     @Nullable final String loaderName,
                                     @Nonnull final String url,
                                     @Nullable final Integer clipWidth,
                                     @Nullable final Integer clipHeight) {
        this.canvasId = canvasId;
        this.loaderName = loaderName;
        this.url = url;
        this.clipWidth = clipWidth;
        this.clipHeight = clipHeight;
    }

    public CanvasId getCanvasId() {
        return canvasId;
    }

    public String getLoaderName() {
        return loaderName;
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
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final CanvasIdWithRenderContext that = (CanvasIdWithRenderContext) obj;
        return url.equals(that.url) &&
               Objects.equals(loaderName, that.loaderName) &&
               Objects.equals(canvasId.getRelativePosition(), that.canvasId.getRelativePosition()) &&
               Objects.equals(clipWidth, that.clipWidth) &&
               Objects.equals(clipHeight, that.clipHeight);
    }

    @Override
    public int hashCode() {
        final MontageRelativePosition relativePosition = canvasId.getRelativePosition();
        int result = url.hashCode();
        result = (31 * result) + (loaderName == null ? 0 : loaderName.hashCode());
        result = (31 * result) + (relativePosition == null ? 0 : relativePosition.hashCode());
        // ignore clipWidth and clipHeight for hashCode since those values
        // should rarely (if ever) differ for canvases with the same URL and relative position
        return result;
    }

    @Override
    public String toString() {
        return "{" +
               "canvasId: '" + canvasId +
               (loaderName == null ? "" : ", stageName: " + loaderName) +
               "', url: '" + url + '\'' +
               (clipWidth == null ? "" : ", clipWidth: " + clipWidth) +
               (clipHeight == null ? "" : ", clipHeight: " + clipHeight) +
               '}';
    }


    public static CanvasIdWithRenderContext build(final CanvasId canvasId,
                                                  final CanvasRenderParametersUrlTemplate urlTemplate) {
        return build(canvasId, null, urlTemplate);
    }

    public static CanvasIdWithRenderContext build(final CanvasId canvasId,
                                                  final String stageName,
                                                  final CanvasRenderParametersUrlTemplate urlTemplate) {
        return new CanvasIdWithRenderContext(canvasId,
                                             stageName,
                                             urlTemplate.getRenderParametersUrl(canvasId),
                                             urlTemplate.getClipWidth(),
                                             urlTemplate.getClipHeight());
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasIdWithRenderContext.class);
}
