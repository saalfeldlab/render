package org.janelia.render.client.intensityadjust;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import fit.util.MatrixFunctions;
import mpicbg.models.AbstractModel;
import mpicbg.models.Affine1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * @author Michael Innerberger and Stephan Preibisch
 */
public class QuadraticModel1D extends AbstractModel<QuadraticModel1D> implements Affine1D<QuadraticModel1D>, Quadratic1D<QuadraticModel1D> {
	// TODO: right now, this implements Affine1D to have a common interface with AffineModel1D; this should be changed when an alternative is available!
	private static final long serialVersionUID = 3144894252699485124L;

	protected final int minNumMatches = 3;
	protected double a, b, c; // a*x*x + b*x + c

	public QuadraticModel1D() {
		this(0.0, 1.0, 0.0);
	}

	public QuadraticModel1D(final double a, final double b, final double c) {
		this.a = a;
		this.b = b;
		this.c = c;
	}

	public static void main(final String[] args) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final ArrayList<PointMatch> candidates = new ArrayList<>();
		final ArrayList<PointMatch> inliers = new ArrayList<>();

		candidates.add(new PointMatch(new Point(new double[]{ 1.0 }), new Point(new double[]{  1.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 2.0 }), new Point(new double[]{  4.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 3.0 }), new Point(new double[]{  9.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 4.0 }), new Point(new double[]{ 16.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 5.0 }), new Point(new double[]{ 15.0 }), 1.0)); // outlier
		candidates.add(new PointMatch(new Point(new double[]{ 6.0 }), new Point(new double[]{ 36.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 7.0 }), new Point(new double[]{ 49.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 8.0 }), new Point(new double[]{ 64.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{ 9.0 }), new Point(new double[]{ 81.0 }), 1.0));
		candidates.add(new PointMatch(new Point(new double[]{10.0 }), new Point(new double[]{999.0 }), 1.0)); // outlier
		candidates.add(new PointMatch(new Point(new double[]{ 5.5 }), new Point(new double[]{ 30.0 }), 0.0)); // weight 0.0

		// Using the polynomial model to do the fitting
		final long startTime = System.nanoTime();
		final QuadraticModel1D regression = new QuadraticModel1D();

		regression.ransac(candidates, inliers, 100, 0.5, 0.5);

		System.out.println("inliers: " + inliers.size());
		System.out.println(regression);
		for (final PointMatch p : candidates) {
			p.apply(regression);
			final String outlier = inliers.contains(p) ? "" : " (identified as outlier)";
			System.out.println("Distance : " + p.getDistance() + outlier);
		}
		regression.fit(inliers);
		final long totalTime = (System.nanoTime() - startTime) / 1000;
		System.out.println("Time: " + totalTime + "us");
	}

	public double getA(){ return a; }
	public double getB(){ return b; }
	public double getC(){ return c; }

	public double getCoefficient(final int j) {
		switch (j) {
			case 0:
				return c;
			case 1:
				return b;
			case 2:
				return a;
			default:
				return 0.0;
		}
	}

	@Override
	public <P extends PointMatch> void fit(final Collection<P> matches) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final int numMatches = matches.size();

		if (numMatches < getMinNumMatches())
			throw new NotEnoughDataPointsException("Not enough points, at least " + getMinNumMatches() + " are necessary and available are: " + numMatches);
		
		final double[] delta = new double[5];
		final double[] theta = new double[3];

		for (final PointMatch match : matches) {
			final double x = match.getP1().getL()[0];
			final double y = match.getP2().getW()[0];
			final double w = match.getWeight();

			// delta = [w*x^4, w*x^3, w*x^2, w*x^1, w]
			double tmp = w;
			delta[4] += tmp;
			tmp *= x;
			delta[3] += tmp;
			tmp *= x;
			delta[2] += tmp;
			tmp *= x;
			delta[1] += tmp;
			tmp *= x;
			delta[0] += tmp;

			// theta = [w*y*x^2, w*y*x, w*y]
			tmp = w * y;
			theta[2] += tmp;
			tmp *= x;
			theta[1] += tmp;
			tmp *= x;
			theta[0] += tmp;
		}

		try {
			solve3x3LinearSystem(delta, theta);
		}
		catch (final NoninvertibleModelException e) {
			this.a = this.b = this.c = 0;
			throw new IllDefinedDataPointsException("Cannot not invert Delta-Matrix, failed to fit function");
		}

		this.a = theta[0];
		this.b = theta[1];
		this.c = theta[2];
	}

	/* compute the solution of the linear system 'delta * x = theta' taking into account the structure:
							  | 0 1 2 |         | 0 1 2 |
	   matrix layout: delta = | . 2 3 |, chol = | . 3 4 |
							  | . . 4 |         | . . 5 |
	   the input theta is overwritten by the solution vector x
 	*/
	private static void solve3x3LinearSystem(double[] delta, double[] theta) throws NoninvertibleModelException {
		// compute row-wise upper triangle of Cholesky factorization U^T*U
		final double[] chol = new double[6];
		chol[0] = Math.sqrt(delta[0]);
		chol[1] = delta[1] / chol[0];
		chol[2] = delta[2] / chol[0];
		chol[3] = Math.sqrt(delta[2] - chol[1]*chol[1]);
		chol[4] = (delta[3] - chol[1]*chol[2]) / chol[3];
		chol[5] = Math.sqrt(delta[4] - chol[2]*chol[2] - chol[4]*chol[4]);

		// determinant = product of diagonal entries of U
		if (Double.isNaN(chol[0]) || Double.isNaN(chol[3]) || Double.isNaN(chol[5])
				|| chol[0] == 0.0 || chol[3] == 0.0 || chol[5] == 0.0)
			throw new NoninvertibleModelException();

		// forward substitution U^T * y = b, where b is stored in theta
		theta[0] /= chol[0];
		theta[1] -= chol[1]*theta[0];
		theta[1] /= chol[3];
		theta[2] -= (chol[2]*theta[0] + chol[4]*theta[1]);
		theta[2] /= chol[5];

		// backward substitution U * x = y, where y is stored in theta
		theta[2] /= chol[5];
		theta[1] -= chol[4]*theta[2];
		theta[1] /= chol[3];
		theta[0] -= (chol[1]*theta[1] + chol[2]*theta[2]);
		theta[0] /= chol[0];

	}


	@Override
	public int getMinNumMatches() {
		return minNumMatches;
	}

	@Override
	public void set(final QuadraticModel1D otherModel) {
		this.a = otherModel.getA();
		this.b = otherModel.getB();
		this.c = otherModel.getC();
		this.cost = otherModel.getCost();
	}

	@Override
	public QuadraticModel1D copy() {
		final QuadraticModel1D newModel = new QuadraticModel1D();
		newModel.set(this);
		return newModel;
	}

	@Override
	public String toString() { return "f(x) = " + getA() + "*x^2 + " + getB() + "*x + " + getC(); }


	@Override
	public double[] apply(final double[] doubles) {
		final double[] copy = doubles.clone();
		applyInPlace(copy);
		return copy;
	}

	@Override
	public void applyInPlace(final double[] doubles) {
		// apply Horner's method
		final double original = doubles[0];
		doubles[0] *= getA();
		doubles[0] += getB();
		doubles[0] *= original;
		doubles[0] += getC();
	}

	@Override
	public void preConcatenate(final QuadraticModel1D model) {
		throw new UnsupportedOperationException("'preConcatenate' not implemented for QuadraticModel1D.");
	}

	@Override
	public void concatenate(final QuadraticModel1D model) {
		throw new UnsupportedOperationException("'concatenate' not implemented for QuadraticModel1D.");
	}

	public void toArray(final double[] target) {
		target[0] = a;
		target[1] = b;
		target[2] = c;
	}

	@Override
	public void toMatrix(final double[][] doubles) {
		throw new UnsupportedOperationException("'toMatrix' not implemented for QuadraticModel1D.");
	}

	@Override
	public QuadraticModel1D createInverse() {
		throw new UnsupportedOperationException("'createInverse' not implemented for QuadraticModel1D.");
	}

	@Override
	public double[] applyInverse(final double[] doubles) throws NoninvertibleModelException {
		throw new UnsupportedOperationException("'applyInverse' not implemented for QuadraticModel1D.");
	}

	@Override
	public void applyInverseInPlace(final double[] doubles) throws NoninvertibleModelException {
		throw new UnsupportedOperationException("'applyInverseInPlace' not implemented for QuadraticModel1D.");
	}
}