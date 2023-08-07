package org.janelia.render.client.newsolver.solvers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.NoninvertibleModelException;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public abstract class Worker < M extends CoordinateTransform, P extends BlockDataSolveParameters< M, P >, F extends BlockFactory< F > > 
{
	// for assigning new id's when splitting BlockData
	final protected int startId;

	final protected BlockData< M, P, F > blockData;
	final protected RenderDataClient renderDataClient;
	final protected String renderStack;

	// what z-range this block covers
	final protected int minZ, maxZ;

	// all z-layers as String map to List that only contains the z-layer as double
	final protected Map<String, ArrayList<Double>> sectionIdToZMap; 

	final protected int numThreads;

	public Worker(
			final int startId,
			final BlockData< M, P, F > blockData,
			final int numThreads )
	{
		this.startId = startId;
		this.blockData = blockData;
		this.renderDataClient =
				new RenderDataClient(
						blockData.solveTypeParameters().baseDataUrl(),
						blockData.solveTypeParameters().owner(),
						blockData.solveTypeParameters().project() );
		this.renderStack = blockData.solveTypeParameters().stack();
		this.sectionIdToZMap = new HashMap<>();

		final Pair<Integer, Integer> minmax = fetchRenderDetails( blockData.idToTileSpec().values(), sectionIdToZMap );

		this.minZ = minmax.getA();
		this.maxZ = minmax.getB();

		this.numThreads = numThreads;
	}

	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }

	/**
	 * runs the Worker
	 */
	public abstract void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException;

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	public abstract List< BlockData< M, P, F > > getBlockDataList();

	public static Pair< Integer, Integer > fetchRenderDetails(
			final Collection< TileSpec > allTileSpecs,
			final Map<String, ArrayList<Double>> sectionIdToZMap )
	{
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for ( final TileSpec t : allTileSpecs) //blockData.idToTileSpec().values() )
		{
			if ( sectionIdToZMap.containsKey( t.getSectionId() ))
			{
				final ArrayList<Double> z = sectionIdToZMap.get( t.getSectionId() );
				
				if ( !z.contains( t.getZ() ) )
					z.add( t.getZ() );
			}
			else
			{
				final ArrayList<Double> z = new ArrayList<>();
				z.add( t.getZ() );
				sectionIdToZMap.put( t.getSectionId(), z );
			}

			final int z = (int)Math.round( t.getZ() );
			minZ = Math.min( z, minZ );
			maxZ = Math.max( z, maxZ );
		}

		return new ValuePair<>( minZ, maxZ );

		//final private HashSet<String> allTileIds;
		//final private Map<String, MinimalTileSpec> idToTileSpec;

		// tileId > String (this is the pId, qId)
		// sectionId > String (this is the pGroupId, qGroupId)
		
		//TileSpec ts = blockData.idToTileSpec().values().iterator().next();
		//String sectionId = ts.getLayout().getSectionId(); // 

	}
}
