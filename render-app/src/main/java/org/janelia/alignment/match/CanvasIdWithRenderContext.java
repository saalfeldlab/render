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

    private transient RenderParameters originalRenderParameters;

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

    public String getGroupId() {
        return canvasId.getGroupId();
    }

    public String getId() {
        return canvasId.getId();
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

        if (originalRenderParameters == null) {
            this.originalRenderParameters = RenderParameters.loadFromUrl(url);
        }

        // always clone loaded parameters so that changes (made for clipping or made later by clients)
        // don't affect cached original
        final RenderParameters renderParametersForRun = cloneRenderParameters(originalRenderParameters);

        if ((clipWidth != null) || (clipHeight != null)) {

            // TODO: setting the canvas offsets here is hack-y, probably want a cleaner way
            canvasId.setClipOffsets(originalRenderParameters.getWidth(),
                                    originalRenderParameters.getHeight(),
                                    clipWidth,
                                    clipHeight);

            clipRenderParameters(canvasId,
                                 clipWidth,
                                 clipHeight,
                                 renderParametersForRun);

            final double[] offsets = canvasId.getClipOffsets();
            LOG.info("loadRenderParameters: loaded {} with offsets ({}, {})", canvasId, offsets[0], offsets[1]);

        } else {
            LOG.info("loadRenderParameters: loaded {}", canvasId);
        }

        return renderParametersForRun;
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
        return build(canvasId, urlTemplate, null, null);
    }

    public static CanvasIdWithRenderContext build(final CanvasId canvasId,
                                                  final CanvasRenderParametersUrlTemplate urlTemplate,
                                                  final String stageName,
                                                  final CanvasIdWithRenderContext matchingPriorStageCanvasId) {

        final CanvasIdWithRenderContext canvasIdWithRenderContext =
                new CanvasIdWithRenderContext(canvasId,
                                              stageName,
                                              urlTemplate.getRenderParametersUrl(canvasId),
                                              urlTemplate.getClipWidth(),
                                              urlTemplate.getClipHeight());

        if ((matchingPriorStageCanvasId != null) &&
            (matchingPriorStageCanvasId.originalRenderParameters != null)) {

            canvasIdWithRenderContext.originalRenderParameters =
                    cloneRenderParameters(matchingPriorStageCanvasId.originalRenderParameters);
            canvasIdWithRenderContext.originalRenderParameters.setScale(urlTemplate.getRenderScale());

            LOG.info("build: cloned render parameters from scale {} to scale {} for {}",
                     matchingPriorStageCanvasId.originalRenderParameters.getScale(),
                     canvasIdWithRenderContext.originalRenderParameters.getScale(),
                     canvasId);
        }

        return canvasIdWithRenderContext;
    }

    /**
     * Clips the bounds of the specified parameters for montage pair point match rendering.
     *
     * @param  canvasId          canvas information.
     * @param  clipWidth         number of full scale pixels to include in clipped members of left/right pairs.
     * @param  clipHeight        number of full scale pixels to include in clipped members of top/bottom pairs.
     * @param  renderParameters  parameters to copy.
     */
    public static void clipRenderParameters(final CanvasId canvasId,
                                            final Integer clipWidth,
                                            final Integer clipHeight,
                                            final RenderParameters renderParameters) {

        final MontageRelativePosition relativePosition = canvasId.getRelativePosition();

        if (relativePosition != null) {

            final double[] clipOffsets = canvasId.getClipOffsets();

            switch (relativePosition) {
                case TOP:
                case BOTTOM:
                    if (clipHeight != null) {
                        renderParameters.y = renderParameters.y + clipOffsets[1];
                        renderParameters.height = clipHeight;
                    }
                    break;
                case LEFT:
                case RIGHT:
                    if (clipWidth != null) {
                        renderParameters.x = renderParameters.x + clipOffsets[0];
                        renderParameters.width = clipWidth;
                    }
                    break;
            }
        }

    }

    private static RenderParameters cloneRenderParameters(final RenderParameters fromParameters)
            throws IllegalArgumentException {

        final RenderParameters clonedParameters;
        try {
            // although this is not the most efficient way clone the parameters,
            // it is easy to read, "correct", and should be sufficient for now
            clonedParameters = RenderParameters.parseJson(fromParameters.toJson());
            clonedParameters.initializeDerivedValues();
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to clone render parameters", e);
        }
        return clonedParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasIdWithRenderContext.class);
}
