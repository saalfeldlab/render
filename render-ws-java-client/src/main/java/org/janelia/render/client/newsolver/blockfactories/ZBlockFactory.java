package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

public class ZBlockFactory implements BlockFactory< ZBlockFactory >, Serializable
{
	private static final long serialVersionUID = 4169473785487008894L;

	final int minZ, maxZ, blockSize, minBlockSize;

	/**
	 * Implementation that balances blocksize using the averageblock size.
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @param minBlockSize - minimal block size (can fail if this is too high to be accommodated)
	 */
	public ZBlockFactory( final int minZ, final int maxZ, final int blockSize, final int minBlockSize )
	{
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSize = blockSize;
		this.minBlockSize = minBlockSize;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M,R,P>> BlockCollection<M, R, P, ZBlockFactory> defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider )
	{
		final List< ZBlockInit > initBlocks = defineBlockLayout( minZ, maxZ, blockSize, minBlockSize );

		final BlockDataSolveParameters< ?,?,? > basicParameters = blockSolveParameterProvider.basicParameters();

		//
		// fetch metadata from render
		//
		final RenderDataClient r = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project() );

		
		final ArrayList< BlockData< M, R, P, ZBlockFactory > > blockDataList = new ArrayList<>();

		// for each block, we know the z-range
		for ( final ZBlockInit initBlock : initBlocks )
		{
			System.out.println( initBlock.id + ": " + initBlock.minZ() + " >> " + initBlock.maxZ() + " [#"+(initBlock.maxZ()-initBlock.minZ()+1) + "]" );

			try
			{
				// TODO: trautmane
				// we fetch all TileSpecs for our z-range
				final ResolvedTileSpecCollection rtsc =
						r.getResolvedTilesForZRange( basicParameters.stack(), (double)initBlock.minZ(), (double)initBlock.maxZ() );

				System.out.println( "Loaded " + rtsc.getTileIds().size() + " tiles.");

				final BlockData< M, R, P, ZBlockFactory > block = 
						new BlockData<>(
								this,
								blockSolveParameterProvider.create( rtsc ),
								initBlock.getId(),
								rtsc );

				blockDataList.add( block );
			}
			catch (final Exception e)
			{
				System.out.println( "Failed to fetch data from render. stopping.");
				e.printStackTrace();
				return null;
			}
		}

		return new BlockCollection<>( blockDataList );
	}

	static class ZBlockInit
	{
		public enum Location { RIGHT, LEFT }

		final int id, min, max;
		final Location location;

		public ZBlockInit( final int id, final int min, final int max, final Location location )
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
	 * Implementation that balances blocksize using the averageblock size.
	 * 
	 * @param minZ - first z slice
	 * @param maxZ - last z slice
	 * @param blockSize - desired block size
	 * @param minBlockSize - minimal block size (can fail if this is too high to be accommodated)
	 * @return overlapping blocks that are solved individually - or null if minBlockSize is too big
	 */
	static List< ZBlockInit > defineBlockLayout( final int minZ, final int maxZ, final int blockSize, final int minBlockSize )
	{
		final ArrayList< ZBlockInit > leftSets = new ArrayList<>();
		final ArrayList< ZBlockInit > rightSets = new ArrayList<>();

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

			final ZBlockInit set = new ZBlockInit( id++, from, to, ZBlockInit.Location.LEFT );
			leftSets.add( set );
			smallestBlock = Math.min( smallestBlock, set.size() );
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final ZBlockInit set0 = leftSets.get( i );
			final ZBlockInit set1 = leftSets.get( i + 1 );

			rightSets.add( new ZBlockInit( id++, ( set0.min + set0.max ) / 2, ( set1.min + set1.max ) / 2 - 1, ZBlockInit.Location.RIGHT ) );
		}

		System.out.println( "smallest block: " + smallestBlock );

		// we can return them in one list since each object knows if it is right or left
		return Stream.concat( leftSets.stream(), rightSets.stream() ).collect( Collectors.toList() );
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?, ?, ZBlockFactory> block) {
		return new ZDistanceWeightFunction(block, 0.01);
	}

	private static class ZDistanceWeightFunction implements WeightFunction {

		private final double midpoint;
		private final double minZ;
		private final double maxZ;
		// regularization to make weights of minZ and maxZ > 0
		private final double eps;

		ZDistanceWeightFunction(final BlockData<?, ?, ?, ZBlockFactory> block, final double eps) {
			this.minZ = block.minZ();
			this.maxZ = block.maxZ();
			this.midpoint = (maxZ + minZ) / 2.0;
			this.eps = eps;
		}

		@Override
		public double compute(final double x, final double y, final double z) {
			final double distanceToBoundary = (z < midpoint) ? (z - minZ) : (maxZ - z);
			return Math.max(0, distanceToBoundary + eps);
		}
	}
}
