package org.janelia.alignment;

import java.awt.BasicStroke;
import java.awt.Color;
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
 *
 * @author Eric Trautman
 */
public class BoundingBoxRenderer {

    private final List<TileSpec> tileSpecs;
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

        this.tileSpecs = renderParameters.getTileSpecs();
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

        Rectangle box;
        for (final TileSpec tileSpec : tileSpecs) {
            box = getScaledBox(tileSpec);
            targetGraphics.draw(box);
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
}
