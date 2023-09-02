package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.IOException;
import java.util.ArrayList;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.intensity.AffineIntensityCorrectionBlockWorker;

import mpicbg.models.AffineModel1D;

/**
 * 
 * @author minnerbe
 *
 * @param <M> the final block solve type (the result)
 */
public class FIBSEMIntensityCorrectionParameters<M>
		extends BlockDataSolveParameters<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> {
	private static final long serialVersionUID = -5349107301431384524L;
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
			final long maxNumberOfCachedPixels,
			final double lambdaTranslation,
			final double lambdaIdentity,
			final double renderScale,
			final int numCoefficients,
			final Integer zDistance) {
		// TODO: properly copy blockSolveModel
		super(baseDataUrl, owner, project, stack, blockSolveModel);

		this.maxNumberOfCachedPixels = maxNumberOfCachedPixels;
		this.lambdaTranslation = lambdaTranslation;
		this.lambdaIdentity = lambdaIdentity;
		this.renderScale = renderScale;
		this.numCoefficients = numCoefficients;
		this.zDistance = zDistance;
	}

	public long maxNumberOfCachedPixels() { return maxNumberOfCachedPixels; }
	public double lambdaTranslation() { return lambdaTranslation; }
	public double lambdaIdentity() { return lambdaIdentity; }
	public double renderScale() { return renderScale; }
	public int numCoefficients() { return numCoefficients; }
	public Integer zDistance() { return zDistance; }

	@Override
	public Worker<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> createWorker(
			final BlockData<M, ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> blockData,
			final int startId,
			final int threadsWorker) {
		try {
			return new AffineIntensityCorrectionBlockWorker<>(blockData, startId, threadsWorker);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
