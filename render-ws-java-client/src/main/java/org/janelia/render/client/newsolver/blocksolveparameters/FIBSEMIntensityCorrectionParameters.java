package org.janelia.render.client.newsolver.blocksolveparameters;

import java.io.IOException;
import java.util.ArrayList;

import mpicbg.models.AffineModel1D;

import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.intensity.AffineIntensityCorrectionBlockWorker;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZDistanceParameters;

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
	private final double crossLayerRenderScale;
	private final int numCoefficients;
	private final ZDistanceParameters zDistance;
	private final double equilibrationWeight;

	public FIBSEMIntensityCorrectionParameters(
			final M blockSolveModel,
			final RenderWebServiceParameters renderWebService,
			final AlgorithmicIntensityAdjustParameters intensityAdjust) {

		this(blockSolveModel,
			 renderWebService.baseDataUrl,
			 renderWebService.owner,
			 renderWebService.project,
			 intensityAdjust.stack,
			 intensityAdjust.maxPixelCacheGb,
			 intensityAdjust.lambda1,
			 intensityAdjust.lambda2,
			 intensityAdjust.renderScale,
			 intensityAdjust.crossLayerRenderScale,
			 intensityAdjust.numCoefficients,
			 intensityAdjust.zDistance,
			 intensityAdjust.equilibrationWeight);
	}

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
			final double crossLayerRenderScale,
			final int numCoefficients,
			final ZDistanceParameters zDistance,
			final double equilibrationWeight) {
		// TODO: properly copy blockSolveModel
		super(baseDataUrl, owner, project, stack, blockSolveModel);

		this.maxNumberOfCachedPixels = maxNumberOfCachedPixels;
		this.lambdaTranslation = lambdaTranslation;
		this.lambdaIdentity = lambdaIdentity;
		this.renderScale = renderScale;
		this.crossLayerRenderScale = crossLayerRenderScale;
		this.numCoefficients = numCoefficients;
		this.zDistance = zDistance;
		this.equilibrationWeight = equilibrationWeight;
	}

	public long maxNumberOfCachedPixels() { return maxNumberOfCachedPixels; }
	public double lambdaTranslation() { return lambdaTranslation; }
	public double lambdaIdentity() { return lambdaIdentity; }
	public double renderScale() { return renderScale; }
	public double crossLayerRenderScale() { return crossLayerRenderScale; }
	public int numCoefficients() { return numCoefficients; }
	public ZDistanceParameters zDistance() { return zDistance; }
	public double equilibrationWeight() { return equilibrationWeight; }

	@Override
	public Worker<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> createWorker(
			final BlockData<ArrayList<AffineModel1D>, FIBSEMIntensityCorrectionParameters<M>> blockData,
			final int threadsWorker) {
		try {
			return new AffineIntensityCorrectionBlockWorker<>(blockData, threadsWorker);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
