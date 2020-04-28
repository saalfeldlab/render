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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import mpicbg.models.AbstractModel;
import mpicbg.models.Affine2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import net.imglib2.util.Pair;

/**
 * @author Stephan Preibisch and Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */

public class StabilizingAffineModel2D< A extends Model< A > & Affine2D< A >, M extends StabilizingAffineModel2D< A, M > > extends AbstractModel< M > implements Affine2D< M >
{
	private static final long serialVersionUID = 4028319936789363770L;

	final List< Pair< List< PointMatch >, ? extends Tile< ? > > > matchesList;
	protected A model;

	public StabilizingAffineModel2D( final A model )
	{
		this.model = model;
		this.matchesList = new ArrayList<>();
	}

	public A getModel()
	{
		return model;
	}

	public void setFitData( final List< Pair< List< PointMatch >, ? extends Tile< ? > > > matchesList )
	{
		this.matchesList.clear();
		this.matchesList.addAll( matchesList );
	}

	@Override
	public int getMinNumMatches()
	{
		return 0;
	}

	@Override
	public < P extends PointMatch > void fit( final Collection< P > m ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		if ( matchesList.isEmpty() )
			return;

		final ArrayList< PointMatch > updatedMatches = new ArrayList<>();

		// TODO: in every iteration, update q with the current group tile transformation(s), the fit p to q for regularization
		for ( final Pair< List< PointMatch >, ? extends Tile< ? > > matchesPair : matchesList )
		{
			final List< PointMatch > matches = matchesPair.getA();
			final Tile< ? > prevTile = matchesPair.getB();

			for ( final PointMatch pm : matches )
			{
				final double[] p = pm.getP1().getL().clone();
				final double[] q = pm.getP2().getL().clone();

				prevTile.getModel().applyInPlace( q ); // this is where we want to sit (stitching + current group tile)

				updatedMatches.add(new PointMatch( new Point(p), new Point(q) ));
			}
		}

		model.fit( updatedMatches );
	}

	@Override
	public void set( final M m ) {}

	@Override
	public M copy()
	{
		@SuppressWarnings( "unchecked" )
		final M copy = ( M )new StabilizingAffineModel2D< A, M >( model.copy() );
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
		final M inverse = ( M )new StabilizingAffineModel2D< A, M >( model.createInverse() );
		inverse.cost = cost;
		return inverse;
	}
}
