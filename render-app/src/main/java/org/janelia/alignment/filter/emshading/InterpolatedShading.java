package org.janelia.alignment.filter.emshading;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

import java.util.Collection;
import java.util.List;
import java.util.function.DoubleBinaryOperator;


/**
 * A model for shading correction in 2D slices of EM data that interpolates between two other models A and B so that
 * the result is A * t + B * (1 - t) for some parameter t in [0, 1].
 */
public class InterpolatedShading extends ShadingModel {

	// NOTE: ideally, ShadingModel would be an interface with an abstract class PolynomialModel containing all the code
	// for polynomial fitting. This is not possible because of type constraints of Model and AbstractModel from
	// mpicbg.models, though. This is why the implementation of the interpolated model feels a bit awkward.

	private final ShadingModel modelA;
	private final ShadingModel modelB;
	private final DoubleBinaryOperator interpolator;

	private final double[] bufferA;
	private final double[] bufferB;

	/**
	 * Create a new interpolated shading model that interpolates linearly between two other models.
	 * @param modelA the first model
	 * @param modelB the second model
	 * @param t the interpolation parameter in [0, 1] so that the result is A * t + B * (1 - t)
	 */
	public InterpolatedShading(final ShadingModel modelA, final ShadingModel modelB, final double t) {
		this.modelA = modelA;
		this.modelB = modelB;
		this.bufferA = new double[2];
		this.bufferB = new double[2];
		this.interpolator = (a, b) -> a * t + b * (1 - t);
	}

	/**
	 * Create a new interpolated shading model that interpolates between two other models using a custom interpolator.
	 * @param modelA the first model
	 * @param modelB the second model
	 * @param interpolator the interpolator to use (should map two values a and b to a value in [a, b])
	 */
	public InterpolatedShading(final ShadingModel modelA, final ShadingModel modelB, final DoubleBinaryOperator interpolator) {
		super();
		this.modelA = modelA;
		this.modelB = modelB;
		this.bufferA = new double[2];
		this.bufferB = new double[2];
		this.interpolator = interpolator;
	}

	@Override
	protected int nCoefficients() {
		if (modelA == null || modelB == null) {
			// this prevents the superclass constructor from throwing an exception
			return 0;
		}
		return Math.max(modelA.nCoefficients(), modelB.nCoefficients());
	}

	@Override
	protected List<String> coefficientNames() {
		throw new UnsupportedOperationException("InterpolatedShading does not support coefficient names");
	}

	@Override
	public void normalize() {
		modelA.normalize();
		modelB.normalize();
	}

	@Override
	public String toString() {
		return "InterpolatedShading{modelA=" + modelA + ", modelB=" + modelB + '}';
	}

	@Override
	protected void fillRowA(final double[] rowA, final double x, final double y) {
		throw new UnsupportedOperationException("InterpolatedShading does not support fillRowA");
	}

	@Override
	public <P extends PointMatch> void fit(final Collection<P> matches) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		modelA.fit(matches);
		modelB.fit(matches);
	}

	@Override
	public void applyInPlace(final double[] location) {
		System.arraycopy(location, 0, bufferA, 0, location.length);
		System.arraycopy(location, 0, bufferB, 0, location.length);
		modelA.applyInPlace(bufferA);
		modelB.applyInPlace(bufferB);
		for (int i = 0; i < location.length; i++) {
			location[i] = interpolator.applyAsDouble(bufferA[i], bufferB[i]);
		}
	}

	@Override
	public double[] getCoefficients() {
		throw new UnsupportedOperationException("InterpolatedShading does not support coefficients");
	}

	@Override
	public InterpolatedShading copy() {
		return new InterpolatedShading(modelA.copy(), modelB.copy(), interpolator);
	}
}
