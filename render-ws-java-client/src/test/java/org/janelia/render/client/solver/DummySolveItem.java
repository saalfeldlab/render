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

	public double getWeight( final int z )
	{
		return 0;
	}

	public double getCosWeight( final int z )
	{
		return 0;
	}
}
