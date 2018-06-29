package org.janelia.alignment;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.List;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple utility to render bounding boxes for tiles.
 * If there is enough area to display them, tile identifiers are also rendered inside each box.
 *
 * @author Eric Trautman
 */
public class BoundingBoxRenderer {

    private final RenderParameters renderParameters;
    private final double xOffset;
    private final double yOffset;
    private final double scale;
    private final Color foregroundColor;
    private final Color backgroundColor;
    private final Stroke stroke;

    public BoundingBoxRenderer(final RenderParameters renderParameters,
                               final Color foregroundColor) {
        this(renderParameters, foregroundColor, 1);
    }

    public BoundingBoxRenderer(final RenderParameters renderParameters,
                               final Color foregroundColor,
                               final float lineWidth) {

        this.renderParameters = renderParameters;
        this.xOffset = renderParameters.getX();
        this.yOffset = renderParameters.getY();
        this.scale = renderParameters.getScale();

        this.foregroundColor = foregroundColor;

        if (renderParameters.getBackgroundRGBColor() == null) {
            this.backgroundColor = null;
        } else {
            this.backgroundColor = new Color(renderParameters.getBackgroundRGBColor());
        }

        this.stroke = new BasicStroke(lineWidth);
    }

    public void render(final BufferedImage targetImage)
            throws IllegalArgumentException {

        final Graphics2D targetGraphics = targetImage.createGraphics();

        targetGraphics.setColor(foregroundColor);
        targetGraphics.setStroke(stroke);

        if (backgroundColor != null) {
            targetGraphics.setBackground(backgroundColor);
            targetGraphics.clearRect(0, 0, targetImage.getWidth(), targetImage.getHeight());
        }

        final List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        final int maxCharactersPerLine = 12;

        int lineWidth = 0;
        int lineHeight = 0;
        int minBoxWidthForTileIdRendering = 0;
        if (tileSpecs.size() > 0) {
            targetGraphics.setFont(TILE_ID_FONT);
            final FontMetrics metrics = targetGraphics.getFontMetrics();
            lineWidth = metrics.stringWidth("A") * maxCharactersPerLine;
            lineHeight = metrics.getHeight();

            // add margin that should be good enough for 'typical' overlap
            minBoxWidthForTileIdRendering = (int) (lineWidth * 1.3) + 1;
        }

        Rectangle box;
        String tileId;
        int x;
        int y;
        int start;
        for (final TileSpec tileSpec : tileSpecs) {

            box = getScaledBox(tileSpec);
            targetGraphics.draw(box);

            if (box.width > minBoxWidthForTileIdRendering) {

                tileId = tileSpec.getTileId();

                if (tileId != null) {
                    x = box.x + ((box.width - lineWidth) / 2); // center tileId horizontally
                    y = box.y + (box.height / 4);              // shift tileId down from top to avoid 'typical' overlap

                    start = 0;
                    for (int stop = maxCharactersPerLine; stop < tileId.length(); stop += maxCharactersPerLine) {
                        targetGraphics.drawString(tileId.substring(start, stop), x, y);
                        y = y + lineHeight;
                        start = stop;
                    }
                    if (start < tileId.length()) {
                        targetGraphics.drawString(tileId.substring(start), x, y);
                    }
                }

            }

        }

        if (renderParameters.isAddWarpFieldDebugOverlay()) {
            WarpFieldDebugRenderer.render(renderParameters,
                                          targetGraphics,
                                          targetImage.getWidth(),
                                          targetImage.getHeight());
        }

        targetGraphics.dispose();

        LOG.debug("render: exit, boxes for {} tiles rendered", tileSpecs.size());
    }

    private Rectangle getScaledBox(final TileSpec tileSpec) {
        final double x = (tileSpec.getMinX() - xOffset) * scale;
        final double y = (tileSpec.getMinY() - yOffset) * scale;
        final double w = ((tileSpec.getMaxX() - xOffset) * scale) - x;
        final double h = ((tileSpec.getMaxY() - yOffset) * scale) - y;
        return new Rectangle((int) x, (int) y, (int) w, (int) h);
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoundingBoxRenderer.class);

    private static final Font TILE_ID_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
}
