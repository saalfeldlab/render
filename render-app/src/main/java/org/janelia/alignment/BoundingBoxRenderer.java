package org.janelia.alignment;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple utility to render bounding boxes for tiles.
 * If there is enough area to display them, tile identifiers are also rendered inside each box.
 *
 * <p>The font size is fixed at {@value #TILE_ID_FONT_SIZE} points regardless of render scale.
 * For each tile the number of characters that fit on one line is computed from the actual
 * scaled box width and the font metrics, so labels wrap correctly at any scale.  If the box
 * is too narrow to display even one character the label is omitted entirely for that tile.</p>
 *
 * @author Eric Trautman
 */
public class BoundingBoxRenderer {

    /** Point size of the font used to render tile identifiers. */
    public static final int TILE_ID_FONT_SIZE = 12;

    /**
     * Fraction of each box dimension reserved as padding margin when deciding whether tile-id
     * text fits.  A value of 1.3 means the text block must fit within ~77% of the box width
     * and height (i.e. {@code usable = boxDimension / TILE_ID_BOX_MARGIN}).
     */
    public static final double TILE_ID_BOX_MARGIN = 1.3;

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

        targetGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        targetGraphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                                        RenderingHints.VALUE_RENDER_QUALITY);

        targetGraphics.setColor(foregroundColor);
        targetGraphics.setStroke(stroke);

        if (backgroundColor != null) {
            targetGraphics.setBackground(backgroundColor);
            targetGraphics.clearRect(0, 0, targetImage.getWidth(), targetImage.getHeight());
        }

        final List<TileSpec> tileSpecs = renderParameters.getTileSpecs();

        FontMetrics metrics = null;
        int lineHeight = 0;
        int charWidth = 0;  // width of a single monospaced character

        if (! tileSpecs.isEmpty()) {
            targetGraphics.setFont(TILE_ID_FONT);
            metrics = targetGraphics.getFontMetrics();
            lineHeight = metrics.getHeight();
            // MONOSPACED: all characters have the same advance width.
            charWidth = metrics.charWidth('A');
        }

        for (final TileSpec tileSpec : tileSpecs) {

            final Rectangle box = getScaledBox(tileSpec);
            targetGraphics.draw(box);

            final String tileId = tileSpec.getTileId();
            if (tileId == null || tileId.isEmpty()) {
                continue;
            }

            // Derive how many margin pixels to leave on each side (same fraction as before).
            final int usableWidth = (int) (box.width / TILE_ID_BOX_MARGIN);

            // How many characters fit on one line inside this box?
            final int charsPerLine = usableWidth / charWidth;

            // If not even one character fits, skip the label for this tile.
            if (charsPerLine < 1) {
                continue;
            }

            // How many lines does this tile id need, and do they fit vertically?
            final List<String> lines = wrapText(tileId, charsPerLine);
            final int totalTextHeight = lines.size() * lineHeight;

            // Only draw if the wrapped text fits inside the usable box height (same margin as width).
            final int usableHeight = (int) (box.height / TILE_ID_BOX_MARGIN);
            if (totalTextHeight > usableHeight) {
                continue;
            }

            // Measure the widest line so we can centre the block horizontally.
            int maxLineWidth = 0;
            for (final String line : lines) {
                final int w = metrics.stringWidth(line);
                if (w > maxLineWidth) {
                    maxLineWidth = w;
                }
            }

            final int x = box.x + (box.width - maxLineWidth) / 2;                    // centre horizontally
            int y = box.y + ((box.height - totalTextHeight) / 2) + metrics.getAscent(); // centre vertically

            for (final String line : lines) {
                targetGraphics.drawString(line, x, y);
                y += lineHeight;
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

    /**
     * Wraps {@code text} into lines of at most {@code maxChars} characters each,
     * breaking only at character boundaries (no word-wrap, matching the original behaviour).
     *
     * @param text      the string to wrap.
     * @param maxChars  maximum number of characters per line (must be &gt;= 1).
     * @return list of lines, never empty.
     */
    static List<String> wrapText(final String text, final int maxChars) {
        final List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            final int end = Math.min(start + maxChars, text.length());
            lines.add(text.substring(start, end));
            start = end;
        }
        return lines;
    }

    private Rectangle getScaledBox(final TileSpec tileSpec) {
        final double x = (tileSpec.getMinX() - xOffset) * scale;
        final double y = (tileSpec.getMinY() - yOffset) * scale;
        final double w = (tileSpec.getMaxX() - xOffset) * scale - x;
        final double h = (tileSpec.getMaxY() - yOffset) * scale - y;
        return new Rectangle((int) x, (int) y, (int) w, (int) h);
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoundingBoxRenderer.class);

    private static final Font TILE_ID_FONT = new Font(Font.MONOSPACED, Font.PLAIN, TILE_ID_FONT_SIZE);
}