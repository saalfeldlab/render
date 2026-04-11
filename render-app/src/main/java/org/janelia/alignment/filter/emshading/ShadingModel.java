package org.janelia.alignment.filter.emshading;

import mpicbg.models.AbstractModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.linsol.LinearSolverDense;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;


/**
 * An abstract base model for shading correction in 2D slices of EM data.
 */
public abstract class ShadingModel extends AbstractModel<ShadingModel> implements Serializable {

	private final double[] coefficients;

	protected abstract int nCoefficients();
	protected abstract void fillRowA(final double[] rowA, final double x, final double y);
	protected abstract List<String> coefficientNames();


	public ShadingModel() {
		coefficients = new double[nCoefficients()];
	}

	public ShadingModel(final double[] coefficients) {
		this();
		System.arraycopy(coefficients, 0, this.coefficients, 0, nCoefficients());
	}

	public double[] getCoefficients() {
		return coefficients;
	}

	/**
	 * Normalize the model so that it approximately integrates to zero over [-1, 1] x [-1, 1].
	 * This assumes that the constant term is stored in the first coefficient.
	 */
	public void normalize() {
		final int N = 10 * nCoefficients();
		final double[] location = new double[2];
		double avg = 0;

		// approximate integral over [-1, 1] x [-1, 1] by averaging the function values at a grid of points
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				location[0] = 2.0 * i / N - 1.0;
				location[1] = 2.0 * j / N - 1.0;
				applyInPlace(location);
				avg += location[0];
			}
		}

		avg /= (N * N);
		coefficients[0] -= avg;
	}

	/**
	 * Transform the given world coordinate (in [min, min + size]) to model coordinates (in [-1, 1]).
	 * @param coordinate the world coordinate to transform
	 * @param min the minimum coordinate of world coordinates
	 * @param size the size of the range of world coordinates
	 * @return the transformed coordinate in the range [-1, 1]
	 */
	public static double toModelCoordinates(
			final double coordinate,
			final double min,
			final double size
	) {
		return 2 * (coordinate - min) / size - 1;
	}

	/**
	 * Transform the given model coordinate (in [-1, 1]) to world coordinates (in [min, min + size]).
	 * @param coordinate the model coordinate to transform
	 * @param min the minimum coordinate of world coordinates
	 * @param size the size of the range of world coordinates
	 * @return the transformed coordinate in the range [min, min + size]
	 */
	public static double fromModelCoordinates(
			final double coordinate,
			final double min,
			final double size
	) {
		return (coordinate + 1) * size / 2 + min;
	}

	@Override
	public int getMinNumMatches() {
		return nCoefficients();
	}

	@Override
	public <P extends PointMatch> void fit(final Collection<P> matches) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final DMatrixRMaj ATA = new DMatrixRMaj(nCoefficients(), nCoefficients(), true, new double[nCoefficients() * nCoefficients()]);
		final DMatrixRMaj ATb = new DMatrixRMaj(nCoefficients(), 1);

		final double[] rowA = new double[nCoefficients()];

		for (final P match : matches) {
			final double x = match.getP1().getL()[0];
			final double y = match.getP1().getL()[1];
			final double z = match.getP2().getL()[0];

			// compute one row of the least-squares matrix A
			fillRowA(rowA, x, y);

			// update upper triangle of A^T * A
			for (int i = 0; i < nCoefficients(); i++) {
				for (int j = i; j < nCoefficients(); j++) {
					ATA.data[i * nCoefficients() + j] += rowA[i] * rowA[j];
				}
			}

			// update right-hand side A^T * b
			for (int i = 0; i < nCoefficients(); i++) {
				ATb.data[i] += rowA[i] * z;
			}
		}

		// set up Cholesky decomposition for A^T * A x = A^T * b (only upper triangle of A^T * A is used)
		final LinearSolverDense<DMatrixRMaj> solver = LinearSolverFactory_DDRM.chol(nCoefficients());
		final boolean success = solver.setA(ATA);
		if (!success) {
			throw new IllDefinedDataPointsException("Matrix factorization failed. This may be due to insufficient or collinear data points.");
		}

		// coefficients are modified in place
		final DMatrixRMaj x = new DMatrixRMaj(nCoefficients(), 1);
		x.setData(coefficients);
		solver.solve(ATb, x);
	}

	@Override
	public double[] apply(final double[] location) {
		final double[] result = location.clone();
		applyInPlace(result);
		return result;
	}

	@Override
	public void set(final ShadingModel model) {
		System.arraycopy(model.getCoefficients(), 0, coefficients, 0, nCoefficients());
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(this.getClass().getSimpleName()).append("{");

		if (coefficients[nCoefficients() - 1] < 0) {
			sb.append("-");
		}

		for (int i = nCoefficients() - 1; i > 0; i--) {
			sb.append(String.format("%.2f", Math.abs(coefficients[i])))
					.append(" ")
					.append(coefficientNames().get(i));

			if (coefficients[i - 1] >= 0) {
				sb.append(" + ");
			} else {
				sb.append(" - ");
			}
		}

		sb.append(String.format("%.2f", Math.abs(coefficients[0])));
		return sb.append("}").toString();
	}
}
