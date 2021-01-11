package org.janelia.render.client.solver;

import java.util.Map;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

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

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected static < G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >
		instantiateSolveItemData(
			final int id,
			final Affine2D<?> globalSolveModel,
			final Affine2D<?> blockSolveModel,
			final Affine2D<?> stitchingModel,
			final int minZ,
			final int maxZ )
	{
		// it will crash here if the models are not Affine2D AND Model
		return new SolveItemData( id, (G)(Object)globalSolveModel, (B)(Object)blockSolveModel, (S)(Object)stitchingModel, minZ, maxZ );
	}
}
