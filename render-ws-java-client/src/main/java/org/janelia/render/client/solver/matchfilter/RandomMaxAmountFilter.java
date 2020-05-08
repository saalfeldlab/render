package org.janelia.render.client.solver.matchfilter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

public class RandomMaxAmountFilter implements MatchFilter
{
	final int maxPoints;

	public RandomMaxAmountFilter( final int maxPoints )
	{
		this.maxPoints = maxPoints;
	}

	@Override
	public List< PointMatch > filter( final Matches matches, final TileSpec pTileSpec, final TileSpec qTileSpec )
	{
        final double[] w = matches.getWs();
        final int numPoints = w.length;

		if ( numPoints <= maxPoints )
			return CanvasMatchResult.convertMatchesToPointMatchList( matches );

		final ArrayList<PointMatch> pointMatchList = new ArrayList<>();

		final double[][] ps = matches.getPs();
		final double[][] qs = matches.getQs();

		// make an index list and randomly remove indices
		final List< Integer > indexList = new LinkedList<>();

		for ( int i = 0; i < numPoints; ++i )
			indexList.add( i );

		final Random rnd = new Random( maxPoints );

		while ( indexList.size() > maxPoints )
			indexList.remove( rnd.nextInt( indexList.size() ) );

		for ( final int i : indexList )
		{
            final double[] pLocal = new double[]{ ps[ 0 ][ i ], ps[ 1 ][ i ] };
            final double[] qLocal = new double[]{ qs[ 0 ][ i ], qs[ 1 ][ i ] };

            pointMatchList.add(new PointMatch(new Point(pLocal), new Point(qLocal), w[i]));			
		}

		return pointMatchList;
	}
}
