package org.janelia.render.client.solver.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mpicbg.models.Affine2D;

import org.apache.commons.lang3.Range;
import org.janelia.render.client.solver.SolveItemData;
import org.janelia.render.client.solver.SolveSet;
import org.janelia.render.client.solver.SolveSetFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SolveSetFactory} implementation that supports excluding ranges of layers from stitch first processing.
 * Custom solvers should extend this class and call {@link #addStitchFirstExclusionRange} in the constructor.
 *
 * One common problem excluding stitch first processing solves is with 2-tile to 1-tile transition areas.
 *
 * This class was refactored from Stephan Preibisch's original {@link SolveSetFactoryBRSec24} implementation
 * so that the logic could be more easily reused for other volumes.
 */
public class SolveSetFactoryWithStitchFirstExclusions
        extends SolveSetFactory
{
	public HashMap<Integer, String> additionalIssues = new HashMap<>();

	private final List<Range<Integer>> stitchFirstExclusionRanges;

	/**
	 * @param defaultGlobalSolveModel - the default model for the final global solve (here always used)
	 * @param defaultBlockSolveModel - the default model (if layer contains no 'restart' or 'problem' tag), otherwise using less stringent model
	 * @param defaultStitchingModel - the default model when stitching per z slice (here always used)
	 * @param defaultBlockOptimizerLambdasRigid - the default rigid/affine lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerLambdasTranslation - the default translation lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerIterations - the default iterations (from parameters)
	 * @param defaultBlockMaxPlateauWidth - the default plateau with (from parameters)
	 * @param defaultMinStitchingInliers - how many inliers per tile pair are necessary for "stitching first"
	 * @param defaultBlockMaxAllowedError - the default max error for global opt (from parameters)
	 * @param defaultDynamicLambdaFactor - the default dynamic lambda factor
	 */
	public SolveSetFactoryWithStitchFirstExclusions(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor )
	{
		super(
				defaultGlobalSolveModel,
				defaultBlockSolveModel,
				defaultStitchingModel,
				defaultBlockOptimizerLambdasRigid,
				defaultBlockOptimizerLambdasTranslation,
				defaultBlockOptimizerIterations,
				defaultBlockMaxPlateauWidth,
				defaultMinStitchingInliers,
				defaultBlockMaxAllowedError,
				defaultDynamicLambdaFactor );
		this.stitchFirstExclusionRanges = new ArrayList<>();
	}

	@Override
	public SolveSet defineSolveSet( final int minZ, final int maxZ, final int setSize, final Map<Integer, String> zToGroupIdMap )
	{
		final int modulo = ( maxZ - minZ + 1 ) % setSize;

		final int numSetsLeft = ( maxZ - minZ + 1 ) / setSize + Math.min( 1, modulo );

		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftSets = new ArrayList<>();
		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightSets = new ArrayList<>();

		int id = 0;

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			final int setMinZ = minZ + i * setSize;
			final int setMaxZ = Math.min( minZ + (i + 1) * setSize - 1, maxZ );

			addSolveItemDataToSets(setMinZ, setMaxZ, zToGroupIdMap, id, leftSets);
			++id;
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SolveItemData< ?, ?, ? > set0 = leftSets.get( i );
			final SolveItemData< ?, ?, ? > set1 = leftSets.get( i + 1 );

			final int setMinZ = ( set0.minZ() + set0.maxZ() ) / 2;
			final int setMaxZ = ( set1.minZ() + set1.maxZ() ) / 2 - 1;

			addSolveItemDataToSets(setMinZ, setMaxZ, zToGroupIdMap, id, rightSets);
			++id;
		}

		return new SolveSet( leftSets, rightSets );
	}

	/**
	 * Add specified z layer range to list of layer ranges to exclude from stitch first processing.
	 *
	 * @param  exclusionRange  minimum and maximum z values to exclude from stitch first processing.
	 */
	public void addStitchFirstExclusionRange(final Range<Integer> exclusionRange) {
		LOG.info("addStitchFirstExclusionRange: entry, adding range {}", exclusionRange);
		stitchFirstExclusionRanges.add(exclusionRange);
	}

	protected static boolean containsIssue(
			final int min,
			final int max,
			final Map<Integer, String> zToGroupIdMap,
			final Map<Integer, String> additionalIssues )
	{
		for ( int i = min; i <= max; ++i )
		{
			if ( zToGroupIdMap.containsKey( i ) || additionalIssues.containsKey( i ) )
				return true;
		}

		return false;
	}

	protected int getMinStitchingInliers(final int setMinZ,
										 final int setMaxZ) {

		int minStitchingInliers = defaultMinStitchingInliers;

		for (final Range<Integer> range : stitchFirstExclusionRanges) {
			if ((setMaxZ >= range.getMinimum()) && (setMinZ <= range.getMaximum())) {
				LOG.info("getMinStitchingInliers: excluding set {}>>{} from stitch first processing",
						 setMinZ, setMaxZ);
				// exclude stitch first processing by setting minStitchingInliers ridiculously high
				minStitchingInliers = 1000000;
				break;
			}
		}

		return minStitchingInliers;
	}

	protected void addSolveItemDataToSets(final int setMinZ,
										  final int setMaxZ,
										  final Map<Integer, String> zToGroupIdMap,
										  final int id,
										  final List<SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>>> sets) {

		boolean rigidPreAlign = false;

		List<Double> blockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
		List<Double> blockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
		List<Integer> blockOptimizerIterations = defaultBlockOptimizerIterations;
		List<Integer> blockMaxPlateauWidth = defaultBlockMaxPlateauWidth;

		if ( containsIssue( setMinZ, setMaxZ, zToGroupIdMap, additionalIssues ) )
		{
			// rigid alignment
			rigidPreAlign = true;

			// Do NOT DO allow rigid stitching
			//stitchingModel = ((InterpolatedAffineModel2D) stitchingModel ).copy();
			//((InterpolatedAffineModel2D) stitchingModel ).setLambda( 1.0 );

			// only rigid/affine solve
			blockOptimizerLambdasRigid = Stream.of(1.0, 0.9, 0.3, 0.01 ).collect(Collectors.toList());
			blockOptimizerLambdasTranslation = Stream.of( 0.0,0.0,0.0,0.0 ).collect(Collectors.toList());
			blockOptimizerIterations = Stream.of( 2000,500,250,250 ).collect(Collectors.toList());
			blockMaxPlateauWidth = Stream.of( 250,150,100,100 ).collect(Collectors.toList());

			LOG.info("addSolveItemDataToSets: set {}>>{} (index {}, id {}) contains issues, using rigid align",
					 setMinZ, setMaxZ, sets.size(), id);
		}

		// set inlier count to default or to ridiculously high number for excluded layers
		// TODO: make also per layer
		final int minStitchingInliers = getMinStitchingInliers(setMinZ, setMaxZ);

		final Affine2D<?> stitchingModelf = defaultStitchingModel;

		sets.add(
				instantiateSolveItemData(
						id,
						this.defaultGlobalSolveModel,
						this.defaultBlockSolveModel,
						(Function< Integer, Affine2D<?> > & Serializable)(z) -> stitchingModelf,
						blockOptimizerLambdasRigid,
						blockOptimizerLambdasTranslation,
						blockOptimizerIterations,
						blockMaxPlateauWidth,
						minStitchingInliers,
						this.defaultBlockMaxAllowedError,
						this.defaultDynamicLambdaFactor,
						rigidPreAlign,
						setMinZ,
						setMaxZ ) );
	}

	private static final Logger LOG = LoggerFactory.getLogger(SolveSetFactoryWithStitchFirstExclusions.class);
}
