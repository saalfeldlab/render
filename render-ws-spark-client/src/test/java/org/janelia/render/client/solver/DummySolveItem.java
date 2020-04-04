package org.janelia.render.client.solver;

import mpicbg.models.AffineModel2D;

public class DummySolveItem extends SolveItem
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
