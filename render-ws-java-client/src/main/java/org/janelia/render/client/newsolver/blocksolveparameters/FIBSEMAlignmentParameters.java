package org.janelia.render.client.newsolver.blocksolveparameters;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Function;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.affine.AffineAlignBlockWorker;
import org.janelia.render.client.parameter.BlockOptimizerParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.StitchingParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;

/**
 * 
 * @author preibischs
 *
 * @param <M> the final block solve type (the result)
 * @param <S> the stitching-first type
 */
public class FIBSEMAlignmentParameters< M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S > > extends BlockDataSolveParameters< M, AffineModel2D, FIBSEMAlignmentParameters< M, S > >
{
	private static final long serialVersionUID = 4247180309556813829L;
	public enum PreAlign { NONE, TRANSLATION, RIGID, MULTI_SEM }

	final private Function< Integer, S > stitchingModelSupplier;
	final private Function< Integer, Integer > minStitchingInliersSupplier; // if it is less, it is not stitched first

	final private StitchingParameters stitchingParameters;
	final private BlockOptimizerParameters blockOptimizerParameters;

	final int preAlignOrdinal; // storing the ordinal of the enum for serialization purposes

	final MatchCollectionParameters matchCollectionParameters;
	final int maxNumMatches;
	final int maxZRangeMatches;

	/**
	 * 
	 * @param blockSolveModel - result model
	 * @param stitchingModelSupplier - returns the stitching model as a function of z
	 * @param minStitchingInliersSupplier - returns minNumStitchingInliers as a function of z (if smaller no stitching first)
	 * @param stitchingParameters - parameters for stitching
	 * @param blockOptimizerParameters - parameters for block optimization
	 * @param preAlign - if and how to pre-align the stack
	 * @param webServiceParameters - url, owner, and project for the web service
	 * @param stack - render stack
	 * @param matchCollectionParameters - owner and collection for matches
	 * @param maxNumMatches - maximal number of matches between two tiles -- will randomly be reduced if above (default: 0 - no limit)
	 * @param maxZRangeMatches - max z-range in which to load matches (default: '-1' - no limit)
	 */
	public FIBSEMAlignmentParameters(
			final M blockSolveModel,
			final Function<Integer, S> stitchingModelSupplier,
			final Function<Integer, Integer> minStitchingInliersSupplier,
			final StitchingParameters stitchingParameters,
			final BlockOptimizerParameters blockOptimizerParameters,
			final PreAlign preAlign,
			final RenderWebServiceParameters webServiceParameters,
			final String stack,
			final MatchCollectionParameters matchCollectionParameters,
			final int maxNumMatches,
			final int maxZRangeMatches)
	{
		super(webServiceParameters.baseDataUrl, webServiceParameters.owner, webServiceParameters.project, stack, blockSolveModel.copy());

		this.stitchingModelSupplier = stitchingModelSupplier;
		this.minStitchingInliersSupplier = minStitchingInliersSupplier;
		this.stitchingParameters = stitchingParameters;
		this.blockOptimizerParameters = blockOptimizerParameters;
		this.preAlignOrdinal = preAlign.ordinal();
		this.matchCollectionParameters = matchCollectionParameters;
		this.maxNumMatches = maxNumMatches;
		this.maxZRangeMatches = maxZRangeMatches;
	}

	@Override
	public M blockSolveModel() { return super.blockSolveModel().copy(); }
	public S stitchingSolveModelInstance(final int z) { return stitchingModelSupplier.apply(z); }
	public Function<Integer, S> stitchingModelSupplier() { return stitchingModelSupplier; }

	public Function<Integer, Integer> minStitchingInliersSupplier() { return minStitchingInliersSupplier; }

	public List<Double> blockOptimizerLambdasRigid() { return blockOptimizerParameters.lambdasRigid; }
	public List<Double> blockOptimizerLambdasTranslation() { return blockOptimizerParameters.lambdasTranslation; }
	public List<Double> blockOptimizerLambdasRegularization() { return blockOptimizerParameters.lambdasRegularization; }
	public List<Integer> blockOptimizerIterations() { return blockOptimizerParameters.iterations; }
	public List<Integer> blockMaxPlateauWidth() {return blockOptimizerParameters.maxPlateauWidth; }
	public double blockMaxAllowedError() { return blockOptimizerParameters.maxAllowedError; }
	public PreAlign preAlign() { return PreAlign.values()[preAlignOrdinal]; }
	public BlockOptimizerParameters blockOptimizerParameters() { return blockOptimizerParameters; }

	public int maxNumMatches() { return maxNumMatches; }
	public int maxZRangeMatches() { return maxZRangeMatches; }
	public String matchOwner() { return matchCollectionParameters.matchOwner; }
	public String matchCollection() { return matchCollectionParameters.matchCollection; }

	public double maxAllowedErrorStitching() { return stitchingParameters.maxAllowedError; }
	public int maxIterationsStitching() { return stitchingParameters.maxIterations; }
	public int maxPlateauWidthStitching() { return stitchingParameters.maxPlateauWidth; }

	@Override
	public Worker<AffineModel2D, FIBSEMAlignmentParameters<M, S>> createWorker(
			final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData,
			final int threadsWorker)
	{
		return new AffineAlignBlockWorker<>(blockData, threadsWorker);
	}

	@Override
	public double[] centerOfMass(final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData)
	{
		if (blockData.getResults().getModelMap() == null || blockData.getResults().getModelMap().isEmpty())
			return super.centerOfMass(blockData);

		// check that all TileSpecs are part of the idToNewModel map
		for (final TileSpec ts : blockData.rtsc().getTileSpecs())
			if (!blockData.getResults().getModelMap().containsKey(ts.getTileId())) {
				LOG.info("WARNING: a TileSpec is not part of the idToNewModel() - that should not happen.");
				return super.centerOfMass(blockData);
			}

		final double[] c = new double[ 3 ];
		int count = 0;

		for ( final TileSpec ts : blockData.rtsc().getTileSpecs() )
		{
			final Rectangle r = ts.toTileBounds().toRectangle();
			final double[] coord = new double[] { r.getCenterX(), r.getCenterY() };

			final AffineModel2D model = blockData.getResults().getModelFor(ts.getTileId());
			model.applyInPlace(coord);

			c[0] += coord[0];
			c[1] += coord[1];
			c[2] += ts.getZ();
			++count;
		}

		c[0] /= count;
		c[1] /= count;
		c[2] /= count;

		return c;
	}

	private static final Logger LOG = LoggerFactory.getLogger(FIBSEMAlignmentParameters.class);
}
