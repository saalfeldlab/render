package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.IOException;
import java.util.List;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.intensity.AffineIntensityCorrectionBlockWorker;
import org.janelia.render.client.parameter.IntensityAdjustParameters;

/**
 * 
 * @author minnerbe
 *
 * @param <M> the final block solve type (the result)
 */
public class FIBSEMIntensityCorrectionParameters<M extends Model<M> & Affine1D<M>>
		extends BlockDataSolveParameters<M, M, FIBSEMIntensityCorrectionParameters<M>> {
	private static final long serialVersionUID = -5349107301431384524L;

	final private List<Double> blockOptimizerLambdasTranslation;
	final private List<Double> blockOptimizerLambdasIdentity;
	final private List<Integer> blockOptimizerIterations;
	final private List<Integer> blockMaxPlateauWidth;

	final private IntensityAdjustParameters intensityParameters;

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

	public String intensityCorrectedFilterStack() { return intensityParameters.intensityCorrectedFilterStack; }
	public long maxNumberOfCachedPixels() { return intensityParameters.getMaxNumberOfCachedPixels(); }
	public double lambdaTranslation() { return intensityParameters.lambda1; }
	public double lambdaIdentity() { return intensityParameters.lambda2; }
	public double renderScale() { return intensityParameters.renderScale; }
	public int numCoefficients() { return intensityParameters.numCoefficients; }
	public Integer zDistance() { return intensityParameters.zDistance; }

	@Override
	public <F extends BlockFactory<F>> Worker<M, M, FIBSEMIntensityCorrectionParameters<M>, F> createWorker(
			final BlockData<M, M, FIBSEMIntensityCorrectionParameters<M>, F> blockData,
			final int startId,
			final int threadsWorker )
	{
		try
		{
			return new AffineIntensityCorrectionBlockWorker<>( blockData, startId, threadsWorker );
		} catch (final IOException e) {
			// TODO: determine if IOException should be propagated instead of wrapping it in a RuntimeException
			throw new RuntimeException("failed to create worker", e); // don't swallow exceptions!
		}
	}
}
