package org.janelia.render.client.solver;

import java.util.List;
import java.util.Map;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public abstract class SolveSetFactory
{
	protected final Affine2D< ? > defaultGlobalSolveModel;
	protected final Affine2D< ? > defaultBlockSolveModel;
	protected final Affine2D< ? > defaultStitchingModel;

	protected final List<Double> defaultBlockOptimizerLambdasRigid;
	protected final List<Double> defaultBlockOptimizerLambdasTranslation;
	protected final List<Integer> defaultBlockOptimizerIterations;
	protected final List<Integer> defaultBlockMaxPlateauWidth;
	protected final int defaultMinStitchingInliers;
	protected final double defaultBlockMaxAllowedError;
	protected final double defaultDynamicLambdaFactor;

	/**
	 * @param defaultGlobalSolveModel - the default model for the final global solve (here always used)
	 * @param defaultBlockSolveModel - the default model (if layer contains no 'restart' or 'problem' tag), otherwise using less stringent model
	 * @param defaultStitchingModel - the default model when stitching per z slice (here always used)
	 * @param defaultBlockOptimizerLambdasRigid - the default rigid/affine lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerLambdasTranslation - the default translation lambdas for a block (from parameters)
	 * @param defaultMinStitchingInliers - how many inliers per tile pair are necessary for "stitching first"
	 * @param defaultBlockOptimizerIterations - the default iterations (from parameters)
	 * @param defaultBlockMaxPlateauWidth - the default plateau with (from parameters)
	 * @param defaultBlockMaxAllowedError - the default max error for global opt (from parameters)
	 * @param defaultDynamicLambdaFactor - the default dynamic lambda factor
	 */
	public SolveSetFactory(
			final Affine2D< ? > defaultGlobalSolveModel,
			final Affine2D< ? > defaultBlockSolveModel,
			final Affine2D< ? > defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor )

	{
		this.defaultGlobalSolveModel = defaultGlobalSolveModel;
		this.defaultBlockSolveModel = defaultBlockSolveModel;
		this.defaultStitchingModel = defaultStitchingModel;
		this.defaultBlockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
		this.defaultBlockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
		this.defaultBlockOptimizerIterations = defaultBlockOptimizerIterations;
		this.defaultBlockMaxPlateauWidth = defaultBlockMaxPlateauWidth;
		this.defaultMinStitchingInliers = defaultMinStitchingInliers;
		this.defaultBlockMaxAllowedError = defaultBlockMaxAllowedError;
		this.defaultDynamicLambdaFactor = defaultDynamicLambdaFactor;
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
			final List<Double> blockOptimizerLambdasRigid,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final int minStitchingInliers,
			final double blockMaxAllowedError,
			final double dynamicLambdaFactor,
			final boolean rigidPreAlign,
			final int minZ,
			final int maxZ )
	{
		// it will crash here if the models are not Affine2D AND Model
		return new SolveItemData(
				id,
				(G)(Object)globalSolveModel,
				(B)(Object)blockSolveModel,
				(S)(Object)stitchingModel,
				blockOptimizerLambdasRigid,
				blockOptimizerLambdasTranslation,
				blockOptimizerIterations,
				blockMaxPlateauWidth,
				minStitchingInliers,
				blockMaxAllowedError,
				dynamicLambdaFactor,
				rigidPreAlign,
				minZ,
				maxZ );
	}
}
