package org.janelia.alignment.transform;

import ij.measure.CurveFitter;
import mpicbg.models.CoordinateTransform;

/**
 * Transform that uses any CurveFitter-fitted function to scale an arbitrary dimension
 * 
 */
public class CurveFitterTransform implements CoordinateTransform {

	private CurveFitter cf;
	private int dimension;

	public CurveFitterTransform(final CurveFitter cf, final int dimension) {
		this.cf = cf;
		this.dimension = dimension;
	}

	@Override
	public double[] apply(final double[] location) {
		final double[] out = location.clone();
		applyInPlace(out);
		return out;
	}

	@Override
	public void applyInPlace(final double[] location) {
		location[dimension] += cf.f(location[dimension]);
	}

	@Override
	public String toString() {
		return cf.getMacroCode() + ", dim=" + dimension;
	}
}
