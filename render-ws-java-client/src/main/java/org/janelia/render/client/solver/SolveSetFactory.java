package org.janelia.render.client.solver;

import java.util.Map;

import mpicbg.models.Affine2D;

public abstract class SolveSetFactory
{
	final Affine2D< ? > defaultGlobalSolveModel;
	final Affine2D< ? > defaultBlockSolveModel;
	final Affine2D< ? > defaultStitchingModel;

	public SolveSetFactory(
			final Affine2D< ? > defaultGlobalSolveModel,
			final Affine2D< ? > defaultBlockSolveModel,
			final Affine2D< ? > defaultStitchingModel )
	{
		this.defaultGlobalSolveModel = defaultGlobalSolveModel;
		this.defaultBlockSolveModel = defaultBlockSolveModel;
		this.defaultStitchingModel = defaultStitchingModel;
	}

	/**
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param setSize - desired block size
	 * @param zToGroupIdMap - a list with known exceptions (restart, problem areas)
	 * @return overlapping blocks that are solved individually
	 */
	public abstract SolveSet defineSolveSet(
			final int minZ,
			final int maxZ,
			final int setSize,
			final Map<Integer, String> zToGroupIdMap );
}
