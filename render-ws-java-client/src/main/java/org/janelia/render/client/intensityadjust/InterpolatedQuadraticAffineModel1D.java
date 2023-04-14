package org.janelia.render.client.intensityadjust;

import mpicbg.models.Affine1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedModel;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

import java.util.Collection;

/**
 * Class that interpolates between a quadratic and an affine model.
 *
 * @param <A> Quadratic model
 * @param <B> Affine model
 */
public class InterpolatedQuadraticAffineModel1D<A extends Model<A> & Quadratic1D<A>, B extends Model<B> & Affine1D<B>>
		extends InterpolatedModel<A, B, InterpolatedQuadraticAffineModel1D<A, B>>
		implements Model<InterpolatedQuadraticAffineModel1D<A, B>>, Quadratic1D<InterpolatedQuadraticAffineModel1D<A, B>> {

	private static final long serialVersionUID = 7416951399166453006L;
	protected QuadraticModel1D quadratic = null;
	protected final double[] afs = new double[3];
	protected final double[] bfs = new double[2];

	public InterpolatedQuadraticAffineModel1D(final A model, final B regularizer, final double lambda) {
		super(model, regularizer, lambda);
		this.interpolate();
	}

	public void interpolate() {
		a.toArray(afs);
		b.toArray(bfs);
		quadratic = new QuadraticModel1D(afs[0] * l1, afs[1] * l1 + bfs[0] * lambda, afs[2] * l1 + bfs[1] * lambda);
	}

	@Override
	public <P extends PointMatch> void fit(final Collection<P> matches) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		super.fit(matches);
		this.interpolate();
	}

	@Override
	public double[] apply(final double[] location) {
		final double[] copy = location.clone();
		this.applyInPlace(copy);
		return copy;
	}

	@Override
	public void applyInPlace(final double[] location) {
		this.quadratic.applyInPlace(location);
	}

	@Override
	public void toArray(final double[] data) {
		this.quadratic.toArray(data);
	}

	@Override
	public InterpolatedQuadraticAffineModel1D<A, B> copy() {
		final InterpolatedQuadraticAffineModel1D<A, B> copy = new InterpolatedQuadraticAffineModel1D<>(a.copy(), b.copy(), lambda);
		copy.cost = this.cost;
		return copy;
	}
}
