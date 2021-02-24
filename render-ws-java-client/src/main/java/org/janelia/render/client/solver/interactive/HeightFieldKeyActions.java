package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.util.Affine3DHelpers;
import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.AbstractTransformAnimator;
import bdv.viewer.animate.RotationAnimator;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.TransformListener;
import net.imglib2.util.LinAlgHelpers;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldKeyActions {

	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval<FloatType> heightField;
	final protected double avg;

	final protected String n5Path;
	final protected String heightFieldDataset;

	// for keystroke actions
	private final ActionMap ksActionMap = new ActionMap();
	private final InputMap ksInputMap = new InputMap();
	private final KeyStrokeAdder ksKeyStrokeAdder;

	public HeightFieldKeyActions(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final double avg,
			final String n5Path,
			final String heightFieldDataset,
			final InputTriggerConfig config,
			final InputActionBindings inputActionBindings) {

		this.viewer = viewer;
		this.heightField = heightField;
		this.avg = avg;
		this.n5Path = n5Path;
		this.heightFieldDataset = heightFieldDataset;

		ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new SaveHeightField("save heightfield", "ctrl S").register();
		new Undo("undo", "ctrl Z").register();
		new GoToZero("go to z=0", "ctrl C").register();
		new DisplayZeroLine("display z=0", viewer, "ctrl 0").register();
		new PrintScale("print current scaling", viewer, "ctrl 2").register();

		new MoveFixedSteps("move horizontal right", 0, false, "ctrl F").register();
		new MoveFixedSteps("move horizontal left", 0, true, "ctrl D").register();

		new MoveFixedSteps("move vertical up", 1, true, "ctrl R").register();
		new MoveFixedSteps("move vertical down", 1, false, "ctrl V").register();

		// TODO: INTENSITY overlay

		inputActionBindings.addActionMap("persistence", ksActionMap);
		inputActionBindings.addInputMap("persistence", ksInputMap);
	}

	private abstract class SelfRegisteringAction extends AbstractNamedAction {

		private static final long serialVersionUID = -1032489117210681503L;

		private final String[] defaultTriggers;

		public SelfRegisteringAction(final String name, final String... defaultTriggers) {

			super(name);
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {

			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), defaultTriggers);
		}
	}

	public void saveHeightField() throws IOException, InterruptedException, ExecutionException {

		System.out.print("Saving heightfield " + n5Path + ":/" + heightFieldDataset + " ... ");
		final ExecutorService exec = Executors.newFixedThreadPool(4);
		final N5FSWriter n5 = new N5FSWriter(n5Path);
		N5Utils
				.save(
						heightField,
						n5,
						heightFieldDataset,
						new int[]{1024, 1024},
						new GzipCompression(),
						exec);
		exec.shutdown();
		n5.setAttribute(heightFieldDataset, "avg", avg);
		System.out.println("done.");
	}

	private class GoToZero extends SelfRegisteringAction {

		private static final long serialVersionUID = 1679653174783245445L;

		public GoToZero(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				final AffineTransform3D initialTransform = viewer.state().getViewerTransform();

				//
				// simply set z to zero (not good)
				//

				// initialTransform.set(0, 3, 4);

				//
				// better, turn to xy, set to z=0, turn back
				//

				// xy plane quaternion
				final double[] qTarget = new double[]{1, 0, 0, 0};

				// quaternion of the current viewer transformation
				final double[] qTargetBack = new double[4];
				Affine3DHelpers.extractRotation(initialTransform, qTargetBack);

				// mouse coordinates
				final Point p = new Point(2);
				viewer.getMouseCoordinates(p);
				final double centerX = p.getIntPosition(0);
				final double centerY = p.getIntPosition(1);

				// use Tobias's code to compute the rotation around the point
				// (amount == 1.0)
				final AffineTransform3D xyPlaneTransform = new RotationAnimator(
						initialTransform,
						centerX,
						centerY,
						qTarget,
						300).get(1);

				// set z to 0.0
				xyPlaneTransform.set(0, 3, 4);

				// use Tobias's code to compute the rotation around the point
				// back to the original orientation (amount == 1.0)
				final AffineTransform3D finalTransform = new RotationAnimator(
						xyPlaneTransform,
						centerX,
						centerY,
						qTargetBack,
						300).get(1);

				//
				// update new transformation
				//
				viewer.state().setViewerTransform(finalTransform);
				viewer.requestRepaint();

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	private class SaveHeightField extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		public SaveHeightField(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					saveHeightField();
				} catch (final IOException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	private class MoveFixedSteps extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;
		final int dim;
		final boolean fwd;
		final int[] steps = new int[]{2000, 1500, 100};

		public MoveFixedSteps(final String name, final int dim, final boolean fwd, final String... defaultTriggers) {

			super(name, defaultTriggers);
			this.dim = dim;
			this.fwd = fwd;
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				final AffineTransform3D viewerTransform = viewer.state().getViewerTransform();

				final double scale = computeScale(viewerTransform);

				final double[] tStart = new double[]{viewerTransform.get(0, 3), viewerTransform.get(1, 3),
						viewerTransform.get(2, 3)};
				final double[] tEnd = tStart.clone();

				if (fwd)
					tEnd[dim] += steps[dim] * scale;
				else
					tEnd[dim] -= steps[dim] * scale;

				viewer.setTransformAnimator(new TranslationTransformAnimator(viewerTransform, tStart, tEnd, 300));

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	protected static class TranslationTransformAnimator extends AbstractTransformAnimator {

		final AffineTransform3D viewerTransform;
		final double[] tStart;
		final double[] tEnd;

		public TranslationTransformAnimator(
				final AffineTransform3D viewerTransform,
				final double[] tStart,
				final double[] tEnd,
				final long duration) {

			super(duration);

			this.viewerTransform = viewerTransform;
			this.tStart = tStart;
			this.tEnd = tEnd;
		}

		@Override
		public AffineTransform3D get(final double t) {

			final AffineTransform3D transform = viewerTransform.copy();

			transform.set(tStart[0] * (1.0 - t) + tEnd[0] * t, 0, 3);
			transform.set(tStart[1] * (1.0 - t) + tEnd[1] * t, 1, 3);
			transform.set(tStart[2] * (1.0 - t) + tEnd[2] * t, 2, 3);

			return transform;
		}

	}

	private class Undo extends SelfRegisteringAction {

		private static final long serialVersionUID = -7208806278835605976L;

		public Undo(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					final N5FSReader n5 = new N5FSReader(n5Path);
					if (n5.datasetExists(heightFieldDataset)) {
						final long[] dimensions = n5.getAttribute(heightFieldDataset, "dimensions", long[].class);
						if (dimensions[0] == heightField.dimension(0) && dimensions[1] == heightField.dimension(1)) {
							AbstractHeightFieldBrushController.copy(N5Utils.open(n5, heightFieldDataset), heightField);
						}
					}
				} catch (final IOException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}

	private class DisplayZeroLine extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		final ViewerPanel viewer;
		ZeroLineOverlay overlay;

		public DisplayZeroLine(final String name, final ViewerPanel viewer, final String... defaultTriggers) {

			super(name, defaultTriggers);

			this.viewer = viewer;
			this.overlay = null;
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				if (overlay == null) {
					this.overlay = new ZeroLineOverlay(viewer);
					viewer.getDisplay().addTransformListener( overlay );
					viewer.getDisplay().addOverlayRenderer( overlay);//.overlays().add(overlay);
				} else {
					overlay.toggleState();
				}

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}

	private class PrintScale extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		final ViewerPanel viewer;
		DisplayScaleOverlay overlay;
		boolean isVisible;

		public PrintScale(final String name, final ViewerPanel viewer, final String... defaultTriggers) {

			super(name, defaultTriggers);

			this.viewer = viewer;
			this.overlay = null;
			this.isVisible = false;
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				if (overlay == null) {
					this.overlay = new DisplayScaleOverlay(viewer);

					viewer.addRenderTransformListener(overlay);
					viewer.getDisplay().addOverlayRenderer(overlay);
				} else {
					overlay.toggleState();
				}

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}

	private static class DisplayScaleOverlay implements OverlayRenderer, TransformListener<AffineTransform3D> {

		private final DecimalFormat format = new DecimalFormat("0.####");
		private final AffineTransform3D viewerTransform;
		private final ViewerPanel viewer;
		private Color col = Color.green.darker();

		private int width = 0, height = 0;
		private boolean draw;

		public DisplayScaleOverlay(final ViewerPanel viewer) {

			this.viewer = viewer;
			this.viewerTransform = new AffineTransform3D();
			this.draw = true;
		}

		@Override
		public void setCanvasSize(final int width, final int height) {

			this.width = width;
			this.height = height;
		}

		@Override
		public void transformChanged(final AffineTransform3D transform) {

			viewerTransform.set(transform);
		}

		public void toggleState() {

			this.draw = !this.draw;
		}

		@Override
		public void drawOverlays(final Graphics g) {

			if (!draw)
				return;

			// scale=det(A)^(1/3);
			final double scale = computeScale(viewerTransform);

			g.setFont(new Font("Monospaced", Font.PLAIN, 12));
			g
					.drawString(
							"s= " + format.format(scale) + " x",
							(int)g.getClipBounds().getWidth() - 100,
							(int)g.getClipBounds().getHeight() - 24);

			// TransformAwareBufferedImageOverlayRenderer t = null;
			// t.bufferedImage;
		}
	}

	public static double computeScale(final AffineTransform3D t) {

		final double[] m = t.getRowPackedCopy();

		// scale=det(A)^(1/3);
		return Math.pow(LinAlgHelpers.det3x3(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]), 1.0 / 3.0);
	}

	private static class ZeroLineOverlay implements OverlayRenderer, TransformListener<AffineTransform3D> {

		private final AffineTransform3D viewerTransform;
		private final ViewerPanel viewer;
		private Color col = Color.green.darker();

		private int width = 0, height = 0;
		private boolean draw;

		public ZeroLineOverlay(final ViewerPanel viewer) {

			this.viewer = viewer;
			this.viewerTransform = new AffineTransform3D();
			this.draw = true;
		}

		@Override
		public void transformChanged(final AffineTransform3D transform) {

			viewerTransform.set(transform);
		}

		public void toggleState() {

			this.draw = !this.draw;
		}

		@Override
		public void drawOverlays(final Graphics g) {

			if (!draw)
				return;

			final Graphics2D graphics = (Graphics2D)g;

			// we have two planes and we want to draw (if visible) the
			// intersecting line
			//
			// http://geomalgorithms.com/a05-_intersect-1.html
			//
			// plane A: the z=0 plane of the volume
			// plane B: the plane that BDV currently displays

			final double[] zero = new double[3];
			final double[] nA = new double[]{0, 0, 1};

			final double[] nATransformed = nA.clone();

			viewerTransform.apply(zero, zero);
			viewerTransform.apply(nA, nATransformed);

			LinAlgHelpers.subtract(nATransformed, zero, nA);
			LinAlgHelpers.normalize(nA);

			final double[] nB = new double[]{0, 0, 1};

			final double dot = LinAlgHelpers.dot(nA, nB);

			// System.out.println( "zero: " +
			// net.imglib2.util.Util.printCoordinates( zero ) );
			// System.out.println( "nATransformed: " +
			// net.imglib2.util.Util.printCoordinates( nATransformed ) );
			// System.out.println( "nA: " +
			// net.imglib2.util.Util.printCoordinates( nA ) );
			// System.out.println( "dot: " + dot );

			if (dot <= 0.9999) // else planes are paralell
			{
				final double[] line = new double[3];

				// compute the direction of the intersecting 3d line
				LinAlgHelpers.cross(nA, nB, line);
				LinAlgHelpers.normalize(line);

				// implicit plane A: dA = –(nA · V0), V0 = any point on the
				// plane
				final double dA = -(LinAlgHelpers.dot(nA, zero)); // zero
																	// transformed
																	// is on
																	// planeA

				// implicit plane B: dB = –(nB · V0), V0 = any point on the
				// plane
				final double dB = -(LinAlgHelpers.dot(nB, new double[3])); // zero
																			// is
																			// on
																			// planeB

				// now we construct a third plane (with the line as normal
				// vector) in order get formula for the intersection line

				final double[] pL = new double[3];
				final double[] tmp = new double[3];

				LinAlgHelpers.subtract(mul(dB, nA), mul(dA, nB), tmp);
				LinAlgHelpers.cross(tmp, line, pL);

				// System.out.println( "P(s) = " +
				// net.imglib2.util.Util.printCoordinates( pL ) + " + s*" +
				// net.imglib2.util.Util.printCoordinates( line ) );

				// now we need to intersect the line with the screen window (it
				// needs to intersect with two of them), it lies in the same
				// plane hence 2d
				final double[] q = new double[]{pL[0], pL[1]};
				final double[] v = new double[]{line[0], line[1]};

				//
				// top screen line
				//
				final double[] p = new double[]{0, 0};
				final double[] u = new double[]{1, 0};

				final double sTop = intersect(p, u, q, v);

				//
				// bottom screen line
				//
				p[0] = 0;
				p[1] = height - 1;

				final double sBottom = intersect(p, u, q, v);

				//
				// left screen line
				//
				p[0] = 0;
				p[1] = 0;

				u[0] = 0;
				u[1] = 1;

				final double sLeft = intersect(p, u, q, v);

				//
				// right screen line
				//
				p[0] = width - 1;
				p[1] = 0;

				u[0] = 0;
				u[1] = 1;

				final double sRight = intersect(p, u, q, v);

				if (Double.isFinite(sTop) && Double.isFinite(sLeft)) {
					// there is still a bug here ... with scaling somehow, works
					// when at least one vector is orthogonal to the screen
					/*
					 * // not orthogonal to x or y
					 * final ArrayList< int[] > points = new ArrayList<>();
					 *
					 * // intersects with the top
					 * if ( sTop >= 0 && sTop <= width - 1 )
					 * points.add( new int[] { (int)Math.round( sTop ), 0 } );
					 *
					 * if ( sBottom >= 0 && sBottom <= width - 1 )
					 * points.add( new int[] { (int)Math.round( sBottom ),
					 * height - 1 } );
					 *
					 * if ( sLeft >= 0 && sLeft <= height - 1 )
					 * points.add( new int[] { 0, (int)Math.round( sLeft ) } );
					 *
					 * if ( sRight >= 0 && sRight <= height - 1 )
					 * points.add( new int[] { width - 1, (int)Math.round(
					 * sRight ) } );
					 *
					 * graphics.setColor( col );
					 *
					 * if ( points.size() == 2 )
					 * {
					 * // normal case, two points
					 * graphics.drawLine(
					 * points.get( 0 )[ 0 ], points.get( 0 )[ 1 ],
					 * points.get( 1 )[ 0 ], points.get( 1 )[ 1 ] );
					 * }
					 * else if ( points.size() > 2 )
					 * {
					 * // this is some edge case, literally, hitting top & left
					 * or/and bottom & right
					 * System.out.println( points.size() + " points!!" );
					 * }
					 */
				} else if (Double.isFinite(sTop) && !Double.isFinite(sLeft)) {
					// vertical line
					graphics.setColor(Color.red);
					graphics.drawLine((int)Math.round(sTop), 0, (int)Math.round(sBottom), height - 1);
				} else if (!Double.isFinite(sTop) && Double.isFinite(sLeft)) {
					// horizontal line
					graphics.setColor(Color.red);
					graphics.drawLine(0, (int)Math.round(sLeft), width - 1, (int)Math.round(sRight));
				} else {
					System.out
							.println(
									"left: " + sLeft + ", top: " + sTop + ", right: " + sRight + ", bottom: "
											+ sBottom);
				}
			}
		}

		private static double intersect(final double[] p, final double[] u, final double[] q, final double[] v) {

			// vector from q to p
			final double[] w = new double[2];
			LinAlgHelpers.subtract(p, q, w);

			return (v[1] * w[0] - v[0] * w[1]) / (v[0] * u[1] - v[1] * u[0]);
		}

		private static double[] mul(final double s, final double[] v) {

			for (int i = 0; i < v.length; ++i)
				v[i] *= s;

			return v;
		}

		@Override
		public void setCanvasSize(final int width, final int height) {

			this.width = width;
			this.height = height;
		}
	}
}
