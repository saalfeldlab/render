package org.janelia.render.client.newsolver.blocksolveparameters;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;

import java.util.List;

/**
 * 
 * @author minnerbe
 *
 * @param <B> the final block solve type (the result)
 */
public class FIBSEMIntensityCorrectionParameters<B extends Model<B> & Affine1D<B>> extends BlockDataSolveParameters<B>
{
	private static final long serialVersionUID = -5349107301431384524L;

	final private B blockSolveModel;

	final private List<Double> blockOptimizerLambdasTranslation;
	final private List<Double> blockOptimizerLambdasIdentity;
	final private List<Integer> blockOptimizerIterations;
	final private List<Integer> blockMaxPlateauWidth;

	final private double blockMaxAllowedError;

	public FIBSEMIntensityCorrectionParameters(
			final B blockSolveModel,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Double> blockOptimizerLambdasIdentity,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final double blockMaxAllowedError,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack)
	{
		super(baseDataUrl, owner, project, stack);

		this.blockSolveModel = blockSolveModel.copy();
		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerLambdasIdentity = blockOptimizerLambdasIdentity;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;
		this.blockMaxAllowedError = blockMaxAllowedError;
	}

	public B blockSolveModelInstance() { return blockSolveModel.copy(); }

	public List<Double> blockOptimizerLambdasTranslation() { return blockOptimizerLambdasTranslation; }
	public List<Double> blockOptimizerLambdasRigid() { return blockOptimizerLambdasIdentity; }
	public List<Integer> blockOptimizerIterations() { return blockOptimizerIterations; }
	public List<Integer> blockMaxPlateauWidth() {return blockMaxPlateauWidth; }
	public double blockMaxAllowedError() { return blockMaxAllowedError; }
}
