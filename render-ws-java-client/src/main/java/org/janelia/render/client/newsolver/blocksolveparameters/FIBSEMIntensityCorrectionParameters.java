package org.janelia.render.client.newsolver.blocksolveparameters;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import org.janelia.render.client.parameter.IntensityAdjustParameters;

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

	final public IntensityAdjustParameters intensityParameters;

	public FIBSEMIntensityCorrectionParameters(
			final M blockSolveModel,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Double> blockOptimizerLambdasIdentity,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final String baseDataUrl,
			final String owner,
			final String project,
			final IntensityAdjustParameters parameters) {
		super(baseDataUrl, owner, project, parameters.stack, blockSolveModel.copy());

		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerLambdasIdentity = blockOptimizerLambdasIdentity;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;
		this.intensityParameters = parameters;
	}

	public List<Double> blockOptimizerLambdasTranslation() { return blockOptimizerLambdasTranslation; }
	public List<Double> blockOptimizerLambdasRigid() { return blockOptimizerLambdasIdentity; }
	public List<Integer> blockOptimizerIterations() { return blockOptimizerIterations; }
	public List<Integer> blockMaxPlateauWidth() {return blockMaxPlateauWidth; }

	@Override
	public FIBSEMIntensityCorrectionParameters<M> createInstance(final boolean hasIssue) {
		return null;
	}
}
