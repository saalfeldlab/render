package org.janelia.render.client.solver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.render.client.solver.SolveSetFactory.SetInit.Location;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public abstract class SolveSetFactory implements Serializable
{
	protected final Affine2D< ? > defaultGlobalSolveModel;
	protected final Affine2D< ? > defaultBlockSolveModel;
	protected final Affine2D< ? > defaultStitchingModel;

	protected final List<Double> defaultBlockOptimizerLambdasRigid;
	protected final List<Double> defaultBlockOptimizerLambdasTranslation;
	protected final List<Integer> defaultBlockOptimizerIterations;
	protected final List<Integer> defaultBlockMaxPlateauWidth;
	protected final int defaultMinStitchingInliers;
	protected final double defaultBlockMaxAllowedError;
	protected final double defaultDynamicLambdaFactor;

	public static class SetInit
	{
		public enum Location { RIGHT, LEFT }

		final int id, min, max;
		final Location location;

		public SetInit( final int id, final int min, final int max, final Location location )
		{
			this.id = id;
			this.min = min;
			this.max = max;
			this.location = location;
		}

		public int getId() { return id; }
		public int minZ() { return min; }
		public int maxZ() { return max; }
		public int size() { return max-min+1; }
		public Location location() { return location; }
	}

	/**
	 * @param defaultGlobalSolveModel - the default model for the final global solve (here always used)
	 * @param defaultBlockSolveModel - the default model (if layer contains no 'restart' or 'problem' tag), otherwise using less stringent model
	 * @param defaultStitchingModel - the default model when stitching per z slice (here always used)
	 * @param defaultBlockOptimizerLambdasRigid - the default rigid/affine lambdas for a block (from parameters)
	 * @param defaultBlockOptimizerLambdasTranslation - the default translation lambdas for a block (from parameters)
	 * @param defaultMinStitchingInliers - how many inliers per tile pair are necessary for "stitching first"
	 * @param defaultBlockOptimizerIterations - the default iterations (from parameters)
	 * @param defaultBlockMaxPlateauWidth - the default plateau with (from parameters)
	 * @param defaultBlockMaxAllowedError - the default max error for global opt (from parameters)
	 * @param defaultDynamicLambdaFactor - the default dynamic lambda factor
	 */
	public SolveSetFactory(
			final Affine2D< ? > defaultGlobalSolveModel,
			final Affine2D< ? > defaultBlockSolveModel,
			final Affine2D< ? > defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor )

	{
		this.defaultGlobalSolveModel = defaultGlobalSolveModel;
		this.defaultBlockSolveModel = defaultBlockSolveModel;
		this.defaultStitchingModel = defaultStitchingModel;
		this.defaultBlockOptimizerLambdasRigid = defaultBlockOptimizerLambdasRigid;
		this.defaultBlockOptimizerLambdasTranslation = defaultBlockOptimizerLambdasTranslation;
		this.defaultBlockOptimizerIterations = defaultBlockOptimizerIterations;
		this.defaultBlockMaxPlateauWidth = defaultBlockMaxPlateauWidth;
		this.defaultMinStitchingInliers = defaultMinStitchingInliers;
		this.defaultBlockMaxAllowedError = defaultBlockMaxAllowedError;
		this.defaultDynamicLambdaFactor = defaultDynamicLambdaFactor;
	}

	/**
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @param minBlockSize - minimal block size (can fail if this is too high to be accommodated)
	 * @param zToGroupIdMap - a list with known exceptions (restart, problem areas)
	 * @return overlapping blocks that are solved individually - or null if minBlockSize is too big
	 */
	public abstract SolveSet defineSolveSet(
			final int minZ,
			final int maxZ,
			final int blockSize,
			final int minBlockSize,
			final Map<Integer, String> zToGroupIdMap );

	/**
	 * Basic implementation that assigns blocks as they come (minBlockSize not supported, last block might have a size of 1)
	 * This implementation was used all through 12/2022
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @return overlapping blocks that are solved individually - or null if minBlockSize is too big
	 */
	public static List< SetInit > defineSolveSetLayout( final int minZ, final int maxZ, final int blockSize )
	{
		final ArrayList< SetInit > leftSets = new ArrayList<>();
		final ArrayList< SetInit > rightSets = new ArrayList<>();

		final int numZ = ( maxZ - minZ + 1 );
		final int modulo = numZ % blockSize;
		final int numSetsLeft = numZ / blockSize + Math.min( 1, modulo ); // add an extra block if there is a modulo

		System.out.println( "numZ: " + numZ );
		System.out.println( "modulo: " + modulo );
		System.out.println( "num blocks: " + ( maxZ - minZ + 1 ) / blockSize );
		System.out.println( "extra block: " + Math.min( 1, modulo ) );
		System.out.println( "total blocks: " + numSetsLeft );

		int id = 0;
		
		for ( int i = 0; i < numSetsLeft; ++i )
			leftSets.add( new SetInit( id++, minZ + i * blockSize, Math.min( minZ + (i + 1) * blockSize - 1, maxZ ), Location.LEFT ) );

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SetInit set0 = leftSets.get( i );
			final SetInit set1 = leftSets.get( i + 1 );

			rightSets.add( new SetInit( id++, ( set0.min + set0.max ) / 2, ( set1.min + set1.max ) / 2 - 1, Location.RIGHT ) );
		}

		// we can return them in one list since each object knows if it is right or left
		return Stream.concat( leftSets.stream(), rightSets.stream() ).collect( Collectors.toList() );
	}

	/**
	 * Implementation that balances blocksize using the averageblock size.
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @param minBlockSize - minimal block size (can fail if this is too high to be accommodated)
	 * @return overlapping blocks that are solved individually - or null if minBlockSize is too big
	 */
	public static List< SetInit > defineSolveSetLayout( final int minZ, final int maxZ, final int blockSize, final int minBlockSize )
	{
		final ArrayList< SetInit > leftSets = new ArrayList<>();
		final ArrayList< SetInit > rightSets = new ArrayList<>();

		final int numZ = ( maxZ - minZ + 1 );
		final int modulo = numZ % blockSize;
		final int numSetsLeft = numZ / blockSize + Math.min( 1, modulo ); // an extra block if there is a modulo
		final int smallestInitialBlock = ((modulo == 0) ? blockSize : modulo );
		final double avgBlock = (double)numZ / (double)numSetsLeft;

		System.out.println( "numZ: " + numZ );
		System.out.println( "modulo: " + modulo );
		System.out.println( "num blocks: " + ( maxZ - minZ + 1 ) / blockSize );
		System.out.println( "extra block: " + Math.min( 1, modulo ) );
		System.out.println( "total blocks: " + numSetsLeft );
		System.out.println( "smallestInitialBlock: " + smallestInitialBlock );
		System.out.println( "avgBlockSize: " + avgBlock );

		// TODO: we could now try to make the blocks bigger or smaller, whenever we balance first, but for now we only try making them smaller because of potential memory issue

		// can we re-balance using the average blocksize
		if ( avgBlock < minBlockSize )
			throw new RuntimeException( "average blocksize=" + avgBlock + " given desired blocksize=" + blockSize + ", which is smaller than minBlockSize=" + minBlockSize + " (either increase blocksize - we're not trying that - or reduce minBlockSize). stopping." );

		int id = 0;
		int smallestBlock = Integer.MAX_VALUE;
		
		for ( int i = 0; i < numSetsLeft; ++i )
		{
			final int from = minZ + (int)Math.round( i * avgBlock );
			final int to = Math.min( minZ + (int)Math.round( (i + 1) * avgBlock ) - 1, maxZ );

			final SetInit set = new SetInit( id++, from, to, Location.LEFT );
			leftSets.add( set );
			smallestBlock = Math.min( smallestBlock, set.size() );
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SetInit set0 = leftSets.get( i );
			final SetInit set1 = leftSets.get( i + 1 );

			rightSets.add( new SetInit( id++, ( set0.min + set0.max ) / 2, ( set1.min + set1.max ) / 2 - 1, Location.RIGHT ) );
		}

		System.out.println( "smallest block: " + smallestBlock );

		// we can return them in one list since each object knows if it is right or left
		return Stream.concat( leftSets.stream(), rightSets.stream() ).collect( Collectors.toList() );
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected static < G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > >
		instantiateSolveItemData(
			final int id,
			final Affine2D<?> globalSolveModel,
			final Affine2D<?> blockSolveModel,
			final Function< Integer, Affine2D<?> > stitchingModelSupplier,
			final List<Double> blockOptimizerLambdasRigid,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final int minStitchingInliers,
			final double blockMaxAllowedError,
			final double dynamicLambdaFactor,
			final boolean rigidPreAlign,
			final int minZ,
			final int maxZ )
	{
		// it will crash here if the models are not Affine2D AND Model
		return new SolveItemData(
				id,
				(G)(Object)globalSolveModel,
				(B)(Object)blockSolveModel,
				(Function< Integer, S > & Serializable)(Object)stitchingModelSupplier,
				blockOptimizerLambdasRigid,
				blockOptimizerLambdasTranslation,
				blockOptimizerIterations,
				blockMaxPlateauWidth,
				minStitchingInliers,
				blockMaxAllowedError,
				dynamicLambdaFactor,
				rigidPreAlign,
				minZ,
				maxZ );
	}
}
