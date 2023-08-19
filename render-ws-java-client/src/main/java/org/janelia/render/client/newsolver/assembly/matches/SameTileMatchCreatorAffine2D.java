package org.janelia.render.client.newsolver.assembly.matches;

import java.util.List;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;

import mpicbg.models.Affine2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * Note: this code would work with any CoordinateTransform, however it is only the right thing to do if the underlying model is some 2D-affine
 * 
 * @author preibischs
 *
 * @param <R> - the result model of the block solves
 */
public class SameTileMatchCreatorAffine2D< R extends Affine2D< R > > implements SameTileMatchCreator< R >
{
	final int samplesPerDimension;

	public SameTileMatchCreatorAffine2D( final int samplesPerDimension )
	{
		this.samplesPerDimension = samplesPerDimension;
	}

	public SameTileMatchCreatorAffine2D() { this( 2 ); }

	@Override
	public void addMatches(
			TileSpec tileSpec,
			R modelA, R modelB,
			BlockData<?, R, ?, ?> blockContextA,
			BlockData<?, R, ?, ?> blockContextB,
			List<PointMatch> matchesAtoB)
	{
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
	}
}
