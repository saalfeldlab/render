package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.PointMatch;

/**
 * A match of two 1D points. This is used in the intensity matching algorithm and adds an optimized method for computing
 * the distance of two 1D points.
 */
public class PointMatch1D extends PointMatch {
	public PointMatch1D(final Point1D p1, final Point1D p2) {
		super(p1, p2);
	}

	public PointMatch1D(final Point1D p1, final Point1D p2, final double weight) {
		super(p1, p2, weight);
	}

	@Override
	public void apply(final CoordinateTransform t) {
		final Point1D p1 = (Point1D) this.p1;
		p1.applyFast(t);
	}

	@Override
	public double getDistance(){
		final Point1D p1 = (Point1D) this.p1;
		final Point1D p2 = (Point1D) this.p2;
		return Math.abs(p1.getL()[0] - p2.getL()[0]);
	}
}
