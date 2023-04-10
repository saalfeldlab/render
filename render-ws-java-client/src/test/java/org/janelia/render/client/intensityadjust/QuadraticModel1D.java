package org.janelia.render.client.intensityadjust;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import fit.util.MatrixFunctions;
import mpicbg.models.AbstractModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * @author Michael Innerberger and Stephan Preibisch
 */
public class QuadraticModel1D extends AbstractModel<QuadraticModel1D> {
	private static final long serialVersionUID = 3144894252699485124L;

	protected final int minNumMatches = 3;
	protected double a, b, c; // a*x*x + b*x + c

	public QuadraticModel1D() {
		this(0, 0, 0);
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

		// compute matrices
		final double[] delta = new double[5];
		final double[] theta = new double[3];

		for (final PointMatch match : matches) {
			final double x = match.getP1().getL()[0];
			final double y = match.getP2().getW()[0];
			final double w = match.getWeight();

			// delta[k] = w * x^k
			double tmp = w;
			for (int k = 0; k < 5; k++) {
				delta[4-k] += tmp;
				tmp *= x;
			}

			// theta[k] = w * y * x^k
			tmp = w * y;
			for (int k = 0; k < 3; k++) {
				theta[2-k] += tmp;
				tmp *= x;
			}
		}

		// invert (symmetric) matrix
		final double[] Ainv = new double[]{
				delta[0], delta[1], delta[2],
				delta[1], delta[2], delta[3],
				delta[2], delta[3], delta[4]};
		try {
			MatrixFunctions.invert3x3(Ainv);
		}
		catch (final NoninvertibleModelException e) {
			this.a = this.b = this.c = 0;
			throw new IllDefinedDataPointsException("Cannot not invert Delta-Matrix, failed to fit function");
		}

		this.a = Ainv[0] * theta[0] + Ainv[1] * theta[1] + Ainv[2] * theta[2];
		this.b = Ainv[3] * theta[0] + Ainv[4] * theta[1] + Ainv[5] * theta[2];
		this.c = Ainv[6] * theta[0] + Ainv[7] * theta[1] + Ainv[8] * theta[2];
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
	public double[] apply(double[] doubles) {
		final double[] copy = Arrays.copyOf(doubles, doubles.length);
		applyInPlace(copy);
		return copy;
	}

	@Override
	public void applyInPlace(double[] doubles) {
		// apply Horner's method
		for (int k = 0; k < doubles.length; k++) {
			final double original = doubles[k];
			doubles[k] *= getA();
			doubles[k] += getB();
			doubles[k] *= original;
			doubles[k] += getC();
		}
	}
}