/**
 *
 */
package org.janelia.render.client.solver.interactive;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import bdv.util.Affine3DHelpers;
import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.OverlayRenderer;

/**
 * An overlay that draws many circles with different colors.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class CircleOverlay implements OverlayRenderer {

	final static protected BasicStroke stroke = new BasicStroke(1);
	final protected ViewerPanel viewer;
	protected final int[] radii;
	protected final Color[] colors;
	final protected AffineTransform3D viewerTransform = new AffineTransform3D();

	protected int x, y, width, height;
	protected boolean visible = false;

	public CircleOverlay(final ViewerPanel viewer, final int[] radii, final Color[] colors) {

		this.viewer = viewer;
		this.radii = radii;
		this.colors = colors;
	}

	public void setPosition(final int x, final int y) {

		this.x = x;
		this.y = y;
	}

	public void setRadius(final int radius, final int i) {

		radii[i] = radius;
	}

	public void setRadii(final int[] radii) {

		System.arraycopy(radii, 0, this.radii, 0, Math.min(radii.length, this.radii.length));
	}

	public void setColor(final Color color, final int i) {

		colors[i] = color;
	}

	public void setColors(final Color[] colors) {

		System.arraycopy(colors, 0, this.colors, 0, Math.min(colors.length, this.colors.length));
	}

	public void setVisible(final boolean visible) {

		this.visible = visible;
	}

	@Override
	public void drawOverlays(final Graphics g) {
		if (visible) {
			final Graphics2D g2d = (Graphics2D)g;

			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.setComposite(AlphaComposite.SrcOver);

			final double scale;
			synchronized (viewer) {
				viewer.getState().getViewerTransform(viewerTransform);
				scale = Affine3DHelpers.extractScale(viewerTransform, 0);
			}

			for (int i = 0; i < radii.length; ++i) {
				final double scaledRadius = scale * radii[i];

				if (x + scaledRadius > 0 &&
						x - scaledRadius < width &&
						y + scaledRadius > 0 &&
						y - scaledRadius < height) {
					final int roundScaledRadius = (int)Math.round(scaledRadius);
					g2d.setColor(colors[i]);
					g2d.setStroke(stroke);
					g2d.drawOval(x - roundScaledRadius, y - roundScaledRadius, 2 * roundScaledRadius + 1, 2 * roundScaledRadius + 1);
				}
			}
		}
	}

	@Override
	public void setCanvasSize(final int width, final int height) {
		this.width = width;
		this.height = height;
	}

}
