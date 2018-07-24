package org.janelia.alignment;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.Point;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.transform.AffineWarpField;
import org.janelia.alignment.transform.AffineWarpFieldTransform;
import org.janelia.alignment.util.DistinctColorStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders an overlay that shows relative transformation vectors for a warp field.
 *
 * @author Eric Trautman
 */
public class WarpFieldDebugRenderer {

    /**
     * Renders an overlay on the target image that shows relative transformation vectors for
     * each grid point in the last warp field for the first tile spec.
     *
     * @param  renderParameters     specifies what to render.
     *                              The first tile spec should have at least one warp field transform.
     *
     * @param  targetImage          target for rendered result.
     */
    public static void render(final RenderParameters renderParameters,
                              final BufferedImage targetImage) {

        final Graphics2D targetGraphics = targetImage.createGraphics();
        render(renderParameters, targetGraphics, targetImage.getWidth(), targetImage.getHeight());
        targetGraphics.dispose();
    }

    /**
     * Renders an overlay that shows relative transformation vectors for
     * each grid point in the last warp field for the first tile spec.
     *
     * @param  renderParameters     specifies what to render.
     *                              The first tile spec should have at least one warp field transform.
     *
     * @param  targetGraphics       target graphics context for the overlay.
     * @param  targetWidth          width of the target image.
     * @param  targetHeight         height of the target image.
     */
    public static void render(final RenderParameters renderParameters,
                              final Graphics2D targetGraphics,
                              final int targetWidth,
                              final int targetHeight) {

        AffineWarpFieldTransform lastWarpFieldTransform = null;

        final List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        if (tileSpecs.size() > 0) {
            final TileSpec firstTileSpec = tileSpecs.get(0);
            final List<CoordinateTransform> firstTileTransformList = new ArrayList<>();
            firstTileSpec.getTransformList().getList(firstTileTransformList);
            for (final CoordinateTransform transform : firstTileTransformList) {
                if (transform instanceof AffineWarpFieldTransform) {
                    lastWarpFieldTransform = (AffineWarpFieldTransform) transform;
                }
            }
        }

        if (lastWarpFieldTransform != null) {

            // adapted from RenderedCanvasMipmapSource.addRenderScaleAndOffset
            final double scale = renderParameters.getScale();
            final AffineModel2D scaleAndOffsetTransform = new AffineModel2D();
            scaleAndOffsetTransform.set(scale,
                                        0,
                                        0,
                                        scale,
                                        -(renderParameters.getX() * scale),
                                        -(renderParameters.getY() * scale));

            drawWarpVectors(scaleAndOffsetTransform, lastWarpFieldTransform, targetGraphics, targetWidth, targetHeight);

        } else {

            LOG.warn("render: first tile spec does not contain a warp field transform");

        }

    }

    /**
     * Draws a source to warped target vector for each grid point in the specified warp field.
     *
     * @param  scaleAndOffsetTransform  scale and offset for current rendering context.
     * @param  warpFieldTransform       warp field transform to "draw".
     * @param  targetGraphics           target graphics context for the overlay.
     * @param  targetWidth              width of the target image.
     * @param  targetHeight             height of the target image.
     */
    private static void drawWarpVectors(final CoordinateTransform scaleAndOffsetTransform,
                                        final AffineWarpFieldTransform warpFieldTransform,
                                        final Graphics2D targetGraphics,
                                        final int targetWidth,
                                        final int targetHeight) {


        final AffineWarpField warpField = warpFieldTransform.getAffineWarpField();

        LOG.info("drawWarpVectors: entry, warpFieldTransform={},  scaleAndOffsetTransform={}",
                 warpFieldTransform, scaleAndOffsetTransform);

        targetGraphics.setStroke(new BasicStroke(2));

        final int targetBoxSize = 8;
        final int halfTargetBoxSize = targetBoxSize / 2;

        final List<Point> gridPoints = warpFieldTransform.getGridPoints();

        final Map<String, Color> affineKeyToColorMap = new HashMap<>();
        final DistinctColorStream colorStream = new DistinctColorStream();

        Color affineColor;
        int index = 0;
        for (final Point gridPoint : gridPoints) {

            // map color for all warp field affine values (even if they are "out of view")
            // so that colors are consistent when viewed through composition client (e.g. CATMAID)
            final String affineKey = getAffineKey(warpFieldTransform.getAffine(gridPoint.getL()));

            affineColor = affineKeyToColorMap.get(affineKey);

            if (affineColor == null) {

                affineColor = colorStream.getNextColor();
                affineKeyToColorMap.put(affineKey, affineColor);

                if (LOG.isDebugEnabled()) {
                    final int row = index / warpField.getColumnCount();
                    final int column = index % warpField.getColumnCount();
                    LOG.debug("drawWarpVectors: new affine found in row {} column {}, key is {}, color is {}",
                              row, column, affineKey, affineColor);
                }
            }

            final double[] source = scaleAndOffsetTransform.apply(gridPoint.getL());

            if ((source[0] >= 0) && (source[1] >= 0) && (source[0] < targetWidth) && (source[1] < targetHeight)) {

                final double[] target = scaleAndOffsetTransform.apply(gridPoint.getW());

                targetGraphics.setColor(affineColor);

                targetGraphics.drawLine((int) source[0], (int) source[1],
                                        (int) target[0], (int) target[1]);
                targetGraphics.drawRect(
                        (int) target[0] - halfTargetBoxSize, (int) target[1] - halfTargetBoxSize,
                        targetBoxSize, targetBoxSize);
            }

            index++;
        }

        LOG.info("drawWarpVectors: exit");
    }

    /**
     * @return a normalized string key for the specified affine that can be
     *         used to identify very similar models.
     */
    private static String getAffineKey(final AffineModel2D model) {
        final double[] m = new double[6];
        model.toArray(m);
        // format each affine value with "significant" digit rounding
        // to ensure very similar values produce the same key
        return String.format("%6.4f %6.4f %6.4f %6.4f %5.1f %5.1f", m[0], m[1], m[2], m[3], m[4], m[5]);
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpFieldDebugRenderer.class);
}
