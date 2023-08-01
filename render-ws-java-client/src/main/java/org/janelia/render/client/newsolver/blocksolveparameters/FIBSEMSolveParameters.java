package org.janelia.render.client.newsolver.blocksolveparameters;

import java.util.List;
import java.util.function.Function;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

/**
 * 
 * @author preibischs
 *
 * @param <B> the final block solve type (the result)
 * @param <S> the stitching-first type
 */
public class FIBSEMSolveParameters< B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends BlockDataSolveParameters< S >
{
	private static final long serialVersionUID = 4247180309556813829L;

	final private B blockSolveModel;
	final private Function< Integer, S > stitchingModelSupplier;
	final private Function< Integer, Integer > minStitchingInliersSupplier; // if it is less, it is not stitched first

	final private List<Double> blockOptimizerLambdasRigid;
	final private List<Double> blockOptimizerLambdasTranslation;
	final private List<Integer> blockOptimizerIterations;
	final private List<Integer> blockMaxPlateauWidth;

	final boolean rigidPreAlign;
	final private double blockMaxAllowedError;

	public FIBSEMSolveParameters(
			final B blockSolveModel,
			final Function< Integer, S > stitchingModelSupplier,
			final Function< Integer, Integer > minStitchingInliersSupplier,
			final List<Double> blockOptimizerLambdasRigid,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final double blockMaxAllowedError,
			final boolean rigidPreAlign,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack)
	{
		super(baseDataUrl, owner, project, stack);

		this.blockSolveModel = blockSolveModel.copy();
		this.stitchingModelSupplier = stitchingModelSupplier;
		this.minStitchingInliersSupplier = minStitchingInliersSupplier;
		this.blockOptimizerLambdasRigid = blockOptimizerLambdasRigid;
		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;
		this.blockMaxAllowedError = blockMaxAllowedError;
		this.rigidPreAlign = rigidPreAlign;
	}

	public B blockSolveModelInstance() { return blockSolveModel.copy(); }

	public S stitchingSolveModelInstance( final int z ) { return stitchingModelSupplier.apply( z ); }
	public Function< Integer, S > stitchingModelSupplier() { return stitchingModelSupplier; }

	public Function< Integer, Integer > minStitchingInliersSupplier() { return minStitchingInliersSupplier; }
	public int minStitchingInliers( final int z ) { return minStitchingInliersSupplier.apply( z ); }

	public List<Double> blockOptimizerLambdasRigid() { return blockOptimizerLambdasRigid; }
	public List<Double> blockOptimizerLambdasTranslation() { return blockOptimizerLambdasTranslation; }
	public List<Integer> blockOptimizerIterations() { return blockOptimizerIterations; }
	public List<Integer> blockMaxPlateauWidth() {return blockMaxPlateauWidth; }
	public double blockMaxAllowedError() { return blockMaxAllowedError; }
	public boolean rigidPreAlign() { return rigidPreAlign; }

}
