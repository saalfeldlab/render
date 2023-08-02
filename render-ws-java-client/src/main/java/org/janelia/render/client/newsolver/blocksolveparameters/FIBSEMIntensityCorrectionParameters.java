package org.janelia.render.client.newsolver.blocksolveparameters;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;

import java.util.List;

/**
 * 
 * @author minnerbe
 *
 * @param <M> the final block solve type (the result)
 */
public class FIBSEMIntensityCorrectionParameters<M extends Model<M> & Affine1D<M>>
		extends BlockDataSolveParameters<M, FIBSEMIntensityCorrectionParameters<M>> {
	private static final long serialVersionUID = -5349107301431384524L;

	final private List<Double> blockOptimizerLambdasTranslation;
	final private List<Double> blockOptimizerLambdasIdentity;
	final private List<Integer> blockOptimizerIterations;
	final private List<Integer> blockMaxPlateauWidth;

	final private double blockMaxAllowedError;

	public FIBSEMIntensityCorrectionParameters(
			final M blockSolveModel,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Double> blockOptimizerLambdasIdentity,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final double blockMaxAllowedError,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack) {
		super(baseDataUrl, owner, project, stack, blockSolveModel.copy());

		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerLambdasIdentity = blockOptimizerLambdasIdentity;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;
		this.blockMaxAllowedError = blockMaxAllowedError;
	}

	public List<Double> blockOptimizerLambdasTranslation() { return blockOptimizerLambdasTranslation; }
	public List<Double> blockOptimizerLambdasRigid() { return blockOptimizerLambdasIdentity; }
	public List<Integer> blockOptimizerIterations() { return blockOptimizerIterations; }
	public List<Integer> blockMaxPlateauWidth() {return blockMaxPlateauWidth; }
	public double blockMaxAllowedError() { return blockMaxAllowedError; }

	@Override
	public FIBSEMIntensityCorrectionParameters<M> createInstance(final boolean hasIssue) {
		return null;
	}
}
