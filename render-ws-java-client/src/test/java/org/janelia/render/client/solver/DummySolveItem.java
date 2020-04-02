package org.janelia.render.client.solver;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

public class DummySolveItem< B extends Model< B > & Affine2D< B > > extends SolveItem< B >
{

	public DummySolveItem( final int z )
	{
		super( z, z, new RunParameters() );

		this.globalAlignAffineModel = new AffineModel2D();
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
