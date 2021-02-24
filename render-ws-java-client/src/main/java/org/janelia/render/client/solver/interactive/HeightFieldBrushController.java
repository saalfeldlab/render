package org.janelia.render.client.solver.interactive;

import java.awt.Color;

import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.viewer.ViewerPanel;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.cache.Cache;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldBrushController extends AbstractHeightFieldBrushController {

	protected final Cache< ?, ? > gradientCache;

	public HeightFieldBrushController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final ScaleAndTranslation heightFieldTransform,
			final Cache< ?, ? > gradientCache,
			final InputTriggerConfig config) {

		super(viewer, heightField, heightFieldTransform, config, new CircleOverlay(viewer, new int[] {5}, new Color[] {Color.WHITE}));

		this.gradientCache = gradientCache;

		new Push( "push", "SPACE button1" ).register();
		new Pull( "erase", "SPACE button2", "SPACE button3" ).register();
		new ChangeBrushRadius( "change brush radius", "SPACE scroll" ).register();
		new MoveBrush( "move brush", "SPACE" ).register();
	}

	protected abstract class AbstractPaintBehavior extends AbstractHeightFieldBrushController.AbstractPaintBehavior {

		public AbstractPaintBehavior(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		abstract protected double getValue();

		@Override
		protected void paint(final RealLocalizable coords)
		{
			final IntervalView<FloatType> heightFieldInterval = Views.offsetInterval(
					zeroExtendedHeightField,
					new long[] {
							Math.round(coords.getDoublePosition(0) - (brushMask.dimension(0) / 2)),
							Math.round(coords.getDoublePosition(1) - (brushMask.dimension(1) / 2))},
					new long[] {
							brushMask.dimension(0),
							brushMask.dimension(1)
					});

			final ArrayCursor<DoubleType> maskCursor = brushMask.cursor();
			final Cursor<FloatType> heightFieldCursor = heightFieldInterval.cursor();

			while (maskCursor.hasNext()) {
				final FloatType v = heightFieldCursor.next();
				v.setReal(maskCursor.next().getRealDouble() * getValue() + v.getRealDouble());
			}

			if ( gradientCache != null )
				gradientCache.invalidateAll();
		}
	}

	protected class Push extends AbstractPaintBehavior {

		public Push(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		protected double getValue() {

			return 0.1;
		}
	}

	protected class Pull extends AbstractPaintBehavior {

		public Pull(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		protected double getValue() {

			return -0.1;
		}
	}
}
