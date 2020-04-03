package org.janelia.render.client.solver;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class DummySolveItem< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends SolveItem< G, B, S >
{
	public DummySolveItem( final G g, final B b, final S s, final int z )
	{
		super( g, b, s, z, z, new RunParameters() );
	}

	@Override
	public double getWeight( final int z )
	{
		return 0;
	}

	@Override
	public double getCosineWeight( final int z )
	{
		return 0;
	}

	@Override
	public double getLinearWeight( final int z )
	{
		return 0;
	}
}
