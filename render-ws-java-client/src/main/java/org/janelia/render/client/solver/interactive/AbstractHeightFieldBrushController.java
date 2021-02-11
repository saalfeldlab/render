package org.janelia.render.client.solver.interactive;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.ScrollBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class AbstractHeightFieldBrushController {

	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval<FloatType> heightField;
	final protected RandomAccessible<FloatType> extendedHeightField, zeroExtendedHeightField;
	final protected ScaleAndTranslation heightFieldTransform;
	final protected RealPoint brushLocation;
	final protected CircleOverlay brushOverlay;
	final protected AffineTransform3D viewerTransform = new AffineTransform3D();
	protected ArrayImg<DoubleType, DoubleArray> brushMask;
	protected double brushSigma = 100;

	// for behavioUrs
	protected final BehaviourMap behaviourMap = new BehaviourMap();
	protected final InputTriggerMap inputTriggerMap = new InputTriggerMap();
	protected final InputTriggerAdder inputAdder;

	protected static ArrayImg<DoubleType, DoubleArray> createMask(final double sigma, final double scale) {

		final double sigmaScaled = sigma / scale;
		final double varScaled = -0.5 / sigmaScaled / sigmaScaled;
		final int radius = (int)Math.round(sigmaScaled * 3);
		final ArrayImg<DoubleType, DoubleArray> brushMask = ArrayImgs.doubles(radius * 2 + 1);
		final FunctionRandomAccessible<DoubleType> gauss = new FunctionRandomAccessible<DoubleType>(
				1,
				(l, t) -> {
					final double x = l.getDoublePosition(0);
					final double v = Math.exp((x * x) * varScaled);
					t.set(v);
				},
				DoubleType::new);

		copy(gauss, Views.translate(brushMask, -radius));

		return brushMask;
	}

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public BehaviourMap getBehaviourMap() {

		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap() {

		return inputTriggerMap;
	}

	public CircleOverlay getBrushOverlay() {

		return brushOverlay;
	}

	public AbstractHeightFieldBrushController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final ScaleAndTranslation heightFieldTransform,
			final InputTriggerConfig config,
			final CircleOverlay brushOverlay) {

		this.viewer = viewer;
		this.heightField = heightField;
		extendedHeightField = Views.extendBorder(this.heightField);
		zeroExtendedHeightField = Views.extendZero(this.heightField);
		this.heightFieldTransform = heightFieldTransform;
		this.brushOverlay = brushOverlay;
		brushMask = createMask(brushSigma, heightFieldTransform.getScale(0));
		inputAdder = config.inputTriggerAdder(inputTriggerMap, "brush");

		brushLocation = new RealPoint(3);
	}

	protected void setCoordinates(final int x, final int y) {

		brushLocation.setPosition(x, 0);
		brushLocation.setPosition(y, 1);
		brushLocation.setPosition(0, 2);

		viewer.displayToGlobalCoordinates(brushLocation);

		heightFieldTransform.applyInverse(brushLocation, brushLocation);
	}

	protected abstract class SelfRegisteringBehaviour implements Behaviour {

		private final String name;

		private final String[] defaultTriggers;

		protected String getName() {

			return name;
		}

		public SelfRegisteringBehaviour(final String name, final String... defaultTriggers) {

			this.name = name;
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {

			behaviourMap.put(name, this);
			inputAdder.put(name, defaultTriggers);
		}
	}

	protected abstract class AbstractPaintBehavior extends SelfRegisteringBehaviour implements DragBehaviour {

		public AbstractPaintBehavior(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		abstract protected void paint(final RealLocalizable coords);

		protected void paint(final int x, final int y) {

			setCoordinates(x, y);
			paint(brushLocation);
		}

		@Override
		public void init( final int x, final int y ) {

			paint(x, y);

			viewer.requestRepaint();
			viewer.getDisplay().repaint();
		}

		@Override
		public void drag(final int x, final int y) {

			brushOverlay.setPosition(x, y);
			paint(x, y);

			viewer.requestRepaint();
			viewer.getDisplay().repaint();
		}

		@Override
		public void end(final int x, final int y) {}
	}

	protected class ChangeBrushRadius extends SelfRegisteringBehaviour implements ScrollBehaviour {

		public ChangeBrushRadius(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void scroll(final double wheelRotation, final boolean isHorizontal, final int x, final int y) {

			if (!isHorizontal) {
				if (wheelRotation < 0)
					brushSigma *= 1.1;
				else if (wheelRotation > 0)
					brushSigma = Math.max(1, brushSigma * 0.9);

				brushOverlay.setRadius(3 * (int)Math.round(brushSigma), 0);
				brushMask = createMask(brushSigma, heightFieldTransform.getScale(0));
				viewer.getDisplay().repaint();
			}
		}
	}

	protected class MoveBrush extends SelfRegisteringBehaviour implements DragBehaviour {

		public MoveBrush(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void init(final int x, final int y) {

			brushOverlay.setPosition(x, y);
			brushOverlay.setVisible(true);
			viewer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
			viewer.getDisplay().repaint();
		}

		@Override
		public void drag(final int x, final int y) {

			brushOverlay.setPosition(x, y);
		}

		@Override
		public void end(final int x, final int y) {

			brushOverlay.setVisible(false);
			viewer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.DEFAULT_CURSOR));
			viewer.getDisplay().repaint();
		}
	}
}
