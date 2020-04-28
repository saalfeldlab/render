/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.render.client.solver;

import java.awt.geom.AffineTransform;
import java.util.Collection;

import mpicbg.models.AbstractModel;
import mpicbg.models.Affine2D;
import mpicbg.models.InterpolatedModel;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;

/**
 * Wraps another models but does not pass through calls to {@link Model#fit}.
 * We use this to let models influence each other combining them in an
 * {@link InterpolatedModel}.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org> and Stephan Preibisch
 */

public class ConstantAffineModel2D< A extends Model< A > & Affine2D< A >, M extends ConstantAffineModel2D< A, M > > extends AbstractModel< M > implements Affine2D< M >
{
	private static final long serialVersionUID = 4028319936789363770L;

	protected A model;

	public ConstantAffineModel2D( final A model )
	{
		this.model = model;
	}

	public A getModel()
	{
		return model;
	}

	public void setModel( final A model )
	{
		this.model = model;
	}

	@Override
	public int getMinNumMatches()
	{
		return 0;
	}

	@Override
	public < P extends PointMatch > void fit( final Collection< P > matches ){}

	@Override
	public void set( final M m ) {}

	@Override
	public M copy()
	{
		@SuppressWarnings( "unchecked" )
		final M copy = ( M )new ConstantAffineModel2D< A, M >( model.copy() );
		copy.cost = cost;
		return copy;
	}

	@Override
	public double[] apply( final double[] location )
	{
		final double[] copy = location.clone();
		applyInPlace( copy );
		return copy;
	}

	@Override
	public void applyInPlace( final double[] location )
	{
		model.applyInPlace( location );
	}

	@Override
	public double[] applyInverse(double[] point) throws NoninvertibleModelException
	{
		return model.applyInverse(point);
	}

	@Override
	public void applyInverseInPlace(double[] point) throws NoninvertibleModelException
	{
		model.applyInverseInPlace(point);
	}

	@Override
	public AffineTransform createAffine()
	{
		return model.createAffine();
	}

	@Override
	public AffineTransform createInverseAffine()
	{
		return model.createInverseAffine();
	}

	@Override
	public void preConcatenate(final M affine2d)
	{
		throw new RuntimeException( "Constant Model cannot preconcatentate" );
	}

	@Override
	public void concatenate(M affine2d)
	{
		throw new RuntimeException( "Constant Model cannot concatentate" );
	}

	@Override
	public void toArray(double[] data)
	{
		model.toArray(data);
	}

	@Override
	public void toMatrix(double[][] data) {
		model.toMatrix(data);
	}

	@Override
	public M createInverse()
	{
		@SuppressWarnings( "unchecked" )
		final M inverse = ( M )new ConstantAffineModel2D< A, M >( model.createInverse() );
		inverse.cost = cost;
		return inverse;
	}
}
