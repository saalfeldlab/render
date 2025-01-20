package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Point;

/**
 * A 1D point. This is used in the intensity matching algorithm and adds an optimized method for applying a
 * transformation a 1D point. The method doesn't overwrite {@link Point#apply(CoordinateTransform)} since this method
 * is marked as final in the superclass.
 */
public class Point1D extends Point {
	public Point1D(final double l, final double w) {
		super(new double[] { l }, new double[] { w });
	}

	public Point1D(final double l) {
		this(l, l);
	}

	public void applyFast(final CoordinateTransform t) {
		w[0] = l[0];
		t.applyInPlace(w);
	}
}
