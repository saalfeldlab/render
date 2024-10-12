package org.janelia.alignment.inpainting;

import net.imglib2.RealInterval;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;


/**
 * Infer missing values in a 2D image by ray casting (which is equivalent to diffusion of image values).
 * <p>
 * This is adapted from the hotknife repository for testing purposes.
 */
public class RayCastingInpainter {

	private final int nRays;
	private final long maxRayLength;
	private final DirectionalStatistic directionStatistic;

	private final double[] direction = new double[2];
	private final Result result = new Result();

	public RayCastingInpainter(final int nRays, final int maxInpaintingDiameter, final DirectionalStatistic directionStatistic) {
		this.nRays = nRays;
		this.maxRayLength = maxInpaintingDiameter;
		this.directionStatistic = directionStatistic;
	}

	private static boolean isInside(final RealLocalizable p, final RealInterval r) {
		for (int d = 0; d < p.numDimensions(); ++d) {
			final double l = p.getDoublePosition(d);
			if (l < r.realMin(d) || l > r.realMax(d)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Inpaints missing values in a 2D image by casting rays in random directions and averaging the values of the
	 * first non-masked pixel.
	 *
	 * @param img the image to inpaint
	 * @param mask the mask
	 */
	public void inpaint(final RandomAccessibleInterval<FloatType> img, final RandomAccessibleInterval<FloatType> mask) {
		final Cursor<FloatType> imgCursor = Views.iterable(img).localizingCursor();

		final RealRandomAccess<FloatType> imageAccess = Views.interpolate(Views.extendBorder(img), new NLinearInterpolatorFactory<>()).realRandomAccess();
		final RealRandomAccess<FloatType> maskAccess = Views.interpolate(Views.extendBorder(mask), new NLinearInterpolatorFactory<>()).realRandomAccess();

		while (imgCursor.hasNext()) {
			final FloatType o = imgCursor.next();
			final float m = maskAccess.setPositionAndGet(imgCursor).get();
			if (m == 0.0) {
				// pixel not masked, no inpainting necessary
				continue;
			}

			double weightSum = 0;
			double valueSum = 0;

			// interpolate value by casting rays in random directions and averaging (weighted by distances) the
			// values of the first non-masked pixel
			for (int i = 0; i < nRays; ++i) {
				final Result result = castRay(maskAccess, mask, imgCursor);
				if (result != null) {
					final double weight = 1.0 / result.distance;
					weightSum += weight;
					final double value = imageAccess.setPositionAndGet(result.position).getRealDouble();
					valueSum += value * weight;
				}
			}

			final float v = (float) (valueSum / weightSum);
			final float w = m / 255.0f;
			final float oldValue = o.get();
			final float newValue = v * w + oldValue * (1 - w);
			o.set(newValue);
		}
	}

	/**
	 * Casts a ray from the given position in a random direction until it hits a non-masked (i.e., non-NaN) pixel
	 * or exits the image boundary.
	 *
	 * @param mask the mask indicating which pixels are masked (> 0) and which are not (0)
	 * @param interval the interval of the image
	 * @param position the position from which to cast the ray
	 * @return the result of the ray casting or null if the ray exited the image boundary without hitting a
	 * 		   non-masked pixel
	 */
	private Result castRay(final RealRandomAccess<FloatType> mask, final Interval interval, final RealLocalizable position) {
		mask.setPosition(position);
		directionStatistic.sample(direction);
		long steps = 0;

		while(true) {
			mask.move(direction);
			++steps;

			if (!isInside(mask, interval) || steps > maxRayLength) {
				// the ray exited the image boundaries without hitting a non-masked pixel
				return null;
			}

			final float value = mask.get().get();
			if (value < 1.0) {
				// the ray reached a non-masked pixel
				mask.localize(result.position);
				result.distance = steps;
				return result;
			}
		}
	}


	private static class Result {
		public double[] position = new double[2];
		public double distance = 0;
	}
}
