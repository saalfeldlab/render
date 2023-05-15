package org.janelia.alignment.intensity;

import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.realtransform.Translation;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import net.imglib2.view.composite.CompositeIntervalView;
import net.imglib2.view.composite.RealComposite;

import java.util.Arrays;

//
public class QuadraticIntensityMap<T extends RealType<T>> {
	public enum Interpolation {NN, NL}

	static private <T extends RealType<T>> InterpolatorFactory<RealComposite<T>, RandomAccessible<RealComposite<T>>> interpolatorFactory(final Interpolation interpolation) {
		switch (interpolation) {
			case NN:
				return new NearestNeighborInterpolatorFactory<>();
			default:
				return new NLinearInterpolatorFactory<>();
		}
	}

	final protected Dimensions dimensions;
	final protected Translation translation;
	final protected RealRandomAccessible<RealComposite<T>> coefficients;

	final protected InterpolatorFactory<RealComposite<T>, RandomAccessible<RealComposite<T>>> interpolatorFactory;

	public QuadraticIntensityMap(final RandomAccessibleInterval<T> source, final InterpolatorFactory<RealComposite<T>, RandomAccessible<RealComposite<T>>> interpolatorFactory) {
		this.interpolatorFactory = interpolatorFactory;
		final CompositeIntervalView<T, RealComposite<T>> collapsedSource = Views.collapseReal(source);
		dimensions = new FinalInterval(collapsedSource);
		final double[] shift = new double[dimensions.numDimensions()];
		Arrays.fill(shift, 0.5);
		translation = new Translation(shift);

		final RandomAccessible<RealComposite<T>> extendedCollapsedSource = Views.extendBorder(collapsedSource);
		coefficients = Views.interpolate(extendedCollapsedSource, interpolatorFactory);
	}

	public QuadraticIntensityMap(final RandomAccessibleInterval<T> source) {
		this(source, new NLinearInterpolatorFactory<>());
	}

	public QuadraticIntensityMap(final RandomAccessibleInterval<T> source, final Interpolation interpolation) {
		this(source, QuadraticIntensityMap.interpolatorFactory(interpolation));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public <S extends NumericType<S>> void run(final RandomAccessibleInterval<S> image) {
		assert image.numDimensions() == dimensions.numDimensions() : "Number of dimensions do not match.";

		final double[] s = new double[dimensions.numDimensions()];
		for (int d = 0; d < s.length; ++d)
			s[d] = image.dimension(d) / dimensions.dimension(d);
		final Scale scale = new Scale(s);

		final RandomAccessibleInterval<RealComposite<T>> stretchedCoefficients =
				Views.offsetInterval(
						Views.raster(
								RealViews.transform(
										RealViews.transform(coefficients, translation),
										scale)),
						image);

		/* decide on type which mapping to use */
		final S t = image.randomAccess().get();

		if (!(t instanceof RealType))
			throw new RuntimeException("Quadratic intensity map only implemented for real types.");

		final RealType<?> r = (RealType) t;
		if (r.getMinValue() > -Double.MAX_VALUE || r.getMaxValue() < Double.MAX_VALUE)
			mapCrop(Views.flatIterable((RandomAccessibleInterval<RealType>) (Object) image), Views.flatIterable(stretchedCoefficients));
		else
			map(Views.flatIterable((RandomAccessibleInterval<RealType>) (Object) image), Views.flatIterable(stretchedCoefficients));
	}

	static protected <S extends RealType<S>, T extends RealType<T>> void map(
			final IterableInterval<S> image,
			final IterableInterval<RealComposite<T>> coefficients) {
		final Cursor<S> cs = image.cursor();
		final Cursor<RealComposite<T>> ct = coefficients.cursor();

		while (cs.hasNext()) {
			final S s = cs.next();
			final RealComposite<T> t = ct.next();
			final double sx = s.getRealDouble();
			s.setReal(sx * sx * t.get(0).getRealDouble() + sx * t.get(1).getRealDouble() + t.get(2).getRealDouble());
		}
	}

	static protected <S extends RealType<S>, T extends RealType<T>> void mapCrop(
			final IterableInterval<S> image,
			final IterableInterval<RealComposite<T>> coefficients) {

		final Cursor<S> cs = image.cursor();
		final Cursor<RealComposite<T>> ct = coefficients.cursor();

		final S firstValue = cs.next();
		final double minS = firstValue.getMinValue();
		final double maxS = firstValue.getMaxValue();

		cs.reset();
		while (cs.hasNext()) {
			final S s = cs.next();
			final RealComposite<T> t = ct.next();
			final double sx = s.getRealDouble();
			final double sxTransformed = sx * sx * t.get(0).getRealDouble() + sx * t.get(1).getRealDouble() + t.get(2).getRealDouble();
			s.setReal(Math.max(minS, Math.min(maxS, sxTransformed)));
		}
	}
}
