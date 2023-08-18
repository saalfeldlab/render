package org.janelia.render.client.newsolver.assembly.matches;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.AffineModel1D;
import mpicbg.models.PointMatch;

public class SameTileMatchCreatorAffineIntensity implements SameTileMatchCreator<ArrayList<AffineModel1D>>
{
	final int samplesPerDimension;

	public SameTileMatchCreatorAffineIntensity( final int samplesPerDimension )
	{
		this.samplesPerDimension = samplesPerDimension;
	}

	public SameTileMatchCreatorAffineIntensity() { this( 2 ); }

	@Override
	public void addMatches(TileSpec tileSpec, ArrayList< AffineModel1D > modelA, ArrayList< AffineModel1D > modelB, List<PointMatch> matchesAtoB)
	{
		// TODO: make 64 matches that map A to p and B to q

		/*
		// make a regular grid
		final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
		final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

		for (int y = 0; y < samplesPerDimension; ++y)
		{
			final double sampleY = y * sampleHeight;
			for (int x = 0; x < samplesPerDimension; ++x)
			{
				final double[] p = new double[] { x * sampleWidth, sampleY };
				final double[] q = new double[] { x * sampleWidth, sampleY };

				modelA.applyInPlace( p );
				modelB.applyInPlace( q );

				matchesAtoB.add(new PointMatch( new Point(p), new Point(q) ));
			}
		}
		*/
	}

}
