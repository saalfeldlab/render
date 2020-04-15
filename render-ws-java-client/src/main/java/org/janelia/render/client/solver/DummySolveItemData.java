package org.janelia.render.client.solver;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class DummySolveItemData< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends SolveItemData< G, B, S >
{
	private static final long serialVersionUID = 343262523978772499L;

	public DummySolveItemData( final G g, final B b, final S s, final int z )
	{
		super( g, b, s, z, z );
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
