package org.janelia.render.client.newsolver.blocksolveparameters;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker;
import org.janelia.render.client.newsolver.solvers.intensity.AffineIntensityCorrectionBlockWorker;
import org.janelia.render.client.parameter.IntensityAdjustParameters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * @author minnerbe
 *
 * @param <M> the final block solve type (the result)
 */
public class FIBSEMIntensityCorrectionParameters<M>
		extends BlockDataSolveParameters<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> {
	private static final long serialVersionUID = -5349107301431384524L;
	private final String intensityCorrectedFilterStack;
	private final long maxNumberOfCachedPixels;
	private final double lambdaTranslation;
	private final double lambdaIdentity;
	private final double renderScale;
	private final int numCoefficients;
	private final Integer zDistance;

	public FIBSEMIntensityCorrectionParameters(
			final M blockSolveModel,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack,
			final String intensityCorrectedFilterStack,
			final long maxNumberOfCachedPixels,
			final double lambdaTranslation,
			final double lambdaIdentity,
			final double renderScale,
			final int numCoefficients,
			final Integer zDistance) {
		// TODO: properly copy blockSolveModel
		super(baseDataUrl, owner, project, stack, blockSolveModel);

		this.intensityCorrectedFilterStack = intensityCorrectedFilterStack;
		this.maxNumberOfCachedPixels = maxNumberOfCachedPixels;
		this.lambdaTranslation = lambdaTranslation;
		this.lambdaIdentity = lambdaIdentity;
		this.renderScale = renderScale;
		this.numCoefficients = numCoefficients;
		this.zDistance = zDistance;
	}

	public String intensityCorrectedFilterStack() { return intensityCorrectedFilterStack; }
	public long maxNumberOfCachedPixels() { return maxNumberOfCachedPixels; }
	public double lambdaTranslation() { return lambdaTranslation; }
	public double lambdaIdentity() { return lambdaIdentity; }
	public double renderScale() { return renderScale; }
	public int numCoefficients() { return numCoefficients; }
	public Integer zDistance() { return zDistance; }

	@Override
	public <F extends BlockFactory<F>> Worker<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, F> createWorker(
			final BlockData<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>, F> blockData,
			final int startId,
			final int threadsWorker) {
		try {
			return new AffineIntensityCorrectionBlockWorker<>(blockData, startId, threadsWorker);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
