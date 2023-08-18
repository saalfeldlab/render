package org.janelia.render.client.newsolver.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.matches.SameTileMatchCreator;
import org.janelia.render.client.newsolver.blockfactories.ZBlockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class ZBlockSolver< Z, G extends Model< G >, R > extends BlockSolver< Z, G, R, ZBlockFactory >
{
	final G globalModel;
	final SameTileMatchCreator< R > sameTileMatchCreator;

	final int maxPlateauWidth;
	final double maxAllowedError;
	final int maxIterations;
	final int numThreads;

	public ZBlockSolver(
			final G globalModel,
			final SameTileMatchCreator< R > sameTileMatchCreator,
			final int maxPlateauWidth,
			final double maxAllowedError,
			final int maxIterations,
			final int numThreads )
	{
		super( globalModel );

		this.globalModel = globalModel;
		this.sameTileMatchCreator = sameTileMatchCreator;
		this.maxPlateauWidth = maxPlateauWidth;
		this.maxAllowedError = maxAllowedError;
		this.maxIterations = maxIterations;
		this.numThreads = numThreads;
	}

	@Override
	public void globalSolve(
			final List< ? extends BlockData<?, R, ?, ZBlockFactory > > blocks,
			final AssemblyMaps< Z > am ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		// local structures required for solvig
		final HashMap<
				Integer,
				ArrayList<
					Pair<
						Pair<
							BlockData<?, R, ?, ZBlockFactory >,
							BlockData<?, R, ?, ZBlockFactory > >,
						HashSet< String > > > > zToBlockPairs = new HashMap<>();
		
		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		final HashMap< BlockData<?, R, ?, ZBlockFactory >, Tile< G > > blockToTile = new HashMap<>();

		// important: all images within one block must be connected to each other!

		LOG.info( "globalSolve: solving {} items", blocks.size() );

		// solve by solveitem, not by z layer
		for ( int a = 0; a < blocks.size() - 1; ++a )
		{
			final BlockData<?, R, ?, ZBlockFactory > solveItemA = blocks.get( a );
			blockToTile.putIfAbsent( solveItemA, new Tile< G >( globalModel.copy() ) );

			for ( int z = solveItemA.minZ(); z <= solveItemA.maxZ(); ++z )
			{
				LOG.info( "globalSolve: solveItemA z range is {} to {}", solveItemA.minZ(), solveItemA.maxZ());

				// is this zPlane of SolveItemA overlapping with anything?
				boolean wasAssigned = false;

				for ( int b = a + 1; b < blocks.size(); ++b )
				{
					final BlockData<?, R, ?, ZBlockFactory > solveItemB = blocks.get( b );
					blockToTile.putIfAbsent( solveItemB, new Tile< G >( globalModel.copy() ) );

					LOG.info( "globalSolve: solveItemB z range is {} to {}", solveItemB.minZ(), solveItemB.maxZ());

					if ( solveItemA.equals( solveItemB ) )
						continue;

					// is overlapping
					if ( z >= solveItemB.minZ() && z <= solveItemB.maxZ() )
					{
						// TODO: this might be unnecessary now
						// every pair exists twice
						if ( pairExists( z, solveItemA, solveItemB, zToBlockPairs ) )
							continue;

						// get tileIds for each z section (they might only be overlapping)
						final HashSet< String > tileIdsA = solveItemA.zToTileId().get( z );
						final HashSet< String > tileIdsB = solveItemB.zToTileId().get( z );

						// if a section is not present
						if ( tileIdsA == null || tileIdsB == null )
							continue;

						// which tileIds are the same between solveItemA and solveItemB
						final HashSet< String > tileIds = commonStrings( tileIdsA, tileIdsB );

						// if there are none, we continue with the next
						if ( tileIds.size() == 0 )
							continue;

						am.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
						zToBlockPairs.putIfAbsent( z, new ArrayList<>() );

						// remember which solveItems defined which tileIds of this z section
						zToBlockPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );

						final List< PointMatch > matchesAtoB = new ArrayList<>();

						for ( final String tileId : tileIds )
						{
							// tilespec is identical for blockA and blockB
							final TileSpec tileSpecAB = solveItemA.rtsc().getTileSpec( tileId );

							// remember the tileids and tileSpecs
							am.zToTileIdGlobal.get( z ).add( tileId );
							am.idToTileSpecGlobal.put( tileId, tileSpecAB );

							final R modelA = solveItemA.idToNewModel().get( tileId );
							final R modelB = solveItemB.idToNewModel().get( tileId );

							sameTileMatchCreator.addMatches( tileSpecAB, modelA, modelB, matchesAtoB );
						}

						final Tile< G > tileA = blockToTile.get( solveItemA );
						final Tile< G > tileB = blockToTile.get( solveItemB );

						tileA.connect( tileB, matchesAtoB );

						tileConfigBlocks.addTile( tileA );
						tileConfigBlocks.addTile( tileB );

						wasAssigned = true;
					}
				}

				// was this zPlane of solveItemA assigned with anything in this run?
				if ( !wasAssigned )
				{
					// if not, the reverse pair might have been assigned before (e.g. 0 and 69, now checking 69 that overlaps only with 0 and 1).
					boolean previouslyAssigned = false;

					if ( zToBlockPairs.containsKey( z ) )
					{
						for ( final Pair< ? extends Pair< ? extends BlockData<?, ?, ?, ? >, ? extends BlockData<?, ?, ?, ? > >, HashSet< String > > entry : zToBlockPairs.get( z ) )
						{
							if ( entry.getA().getA().equals( solveItemA ) || entry.getA().getB().equals( solveItemA ) )
							{
								previouslyAssigned = true;
								break;
							}
						}
					}

					if ( !previouslyAssigned )
					{
						// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
						final HashSet< String > tileIds = solveItemA.zToTileId().get( z );

						// if there are none, we continue with the next
						if ( tileIds.size() == 0 )
							continue;
	
						am.zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
						zToBlockPairs.putIfAbsent( z, new ArrayList<>() );
	
						// remember which solveItems defined which tileIds of this z section
						// TODO: no DummyBlocks anymore, just set it to null, let's see how to fix it down the road
						final BlockData<?, R, ?, ZBlockFactory > solveItemB = null; //solveItemA.createCorrespondingDummySolveItem( id, z );

						zToBlockPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );
						blockToTile.putIfAbsent( solveItemB, new Tile< G >( globalModel.copy() ) );
	
						//++id;

						for ( final String tileId : tileIds )
						{
							//solveItemB.idToNewModel().put( tileId, resultModel.copy() );
	
							// remember the tileids and tileSpecs
							am.zToTileIdGlobal.get( z ).add( tileId );
							am.idToTileSpecGlobal.put( tileId, solveItemA.rtsc().getTileSpec( tileId ) );
						}
					}
				}
			}
		}

		LOG.info( "launching Pre-Align, tileConfigBlocks has {} tiles and {} fixed tiles",
				  tileConfigBlocks.getTiles().size(), tileConfigBlocks.getFixedTiles().size() );

		tileConfigBlocks.preAlign();

		LOG.info( "Optimizing ... " );
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				new ErrorStatistic( maxPlateauWidth + 1 ),
				maxAllowedError,
				maxIterations,
				maxPlateauWidth,
				damp,
				tileConfigBlocks,
				tileConfigBlocks.getTiles(),
				tileConfigBlocks.getFixedTiles(),
				numThreads );

	}

	protected boolean pairExists(
			final int z,
			final BlockData<?, ?, ?, ZBlockFactory > blockA,
			final BlockData<?, ?, ?, ZBlockFactory > blockB,
			final HashMap<Integer, ? extends ArrayList< ? extends Pair< ? extends Pair< ? extends BlockData< ?, ?, ?, ? >, ? extends BlockData< ?, ?, ?, ? > >, HashSet< String > > > > zToBlockPairs )
	{
		if ( zToBlockPairs.containsKey( z ) )
		{
			final ArrayList< ? extends Pair< ? extends Pair< ? extends BlockData< ?, ?, ?, ? >, ? extends BlockData< ?, ?, ?, ? > >, HashSet< String > > > entries = zToBlockPairs.get( z );

			for ( final Pair< ? extends Pair< ? extends BlockData< ?, ?, ?, ? >, ? extends BlockData< ?, ?, ?, ? > >, HashSet< String > > entry : entries )
				if (entry.getA().getA().equals( blockA ) && entry.getA().getB().equals( blockB ) ||
					entry.getA().getA().equals( blockB ) && entry.getA().getB().equals( blockA ) )
						return true;

			return false;
		}
		else
		{
			return false;
		}
	}

	protected static HashSet< String > commonStrings( final HashSet< String > tileIdsA, final HashSet< String > tileIdsB )
	{
		final HashSet< String > commonStrings = new HashSet<>();

		for ( final String a : tileIdsA )
			if ( tileIdsB.contains( a ) )
				commonStrings.add( a );

		return commonStrings;
	}

	private static final Logger LOG = LoggerFactory.getLogger(ZBlockSolver.class);
}