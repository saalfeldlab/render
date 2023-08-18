package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.solvers.Worker;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 * Will need to add this parameter object later rather than extending the class I think
 * 
 * @author preibischs
 *
 * @param <M> - the compute model
 * @param <R> - the result
 * @param <P> - the solve parameters
 * @param <F> - the block factory
 */
public class BlockData< M, R, P extends BlockDataSolveParameters< M, R, P >, F extends BlockFactory< F > > implements Serializable
{
	private static final long serialVersionUID = -6491517262420660476L;

	private int id;

	// the BlockFactory that created this BlockData
	final F blockFactory;

	// contains solve-specific parameters and models
	final P solveTypeParameters;

	// used for saving and display
	final private ResolvedTileSpecCollection rtsc;

	// all z-layers as String map to List that only contains the z-layer as double
	final protected Map<String, ArrayList<Double>> sectionIdToZMap; 

	// what z-range this block covers
	final protected int minZ, maxZ;

	//
	// below are the results that the worker has to fill up
	//

	// used for global solve outside
	final private HashMap<Integer, HashSet<String> > zToTileId = new HashMap<>();

	// contains the model as determined by the local solve
	final private HashMap<String, R> idToNewModel = new HashMap<>();

	// the errors per tile
	final HashMap< String, List< Pair< String, Double > > > idToBlockErrorMap = new HashMap<>();

	public BlockData(
			final F blockFactory, // knows how it was created for assembly later?
			final P solveTypeParameters,
			final int id,
			final ResolvedTileSpecCollection rtsc )
	{
		this.id = id;
		this.blockFactory = blockFactory;
		this.solveTypeParameters = solveTypeParameters;
		this.rtsc = rtsc;

		this.sectionIdToZMap = new HashMap<>();

		// TODO: trautmane
		final Pair<Integer, Integer> minmax = fetchRenderDetails( rtsc.getTileSpecs(), sectionIdToZMap );
		this.minZ = minmax.getA();
		this.maxZ = minmax.getB();
	}

	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }
	public Map<String, ArrayList<Double>> sectionIdToZMap() { return sectionIdToZMap; }

	public int getId() { return id; }
	public ArrayList< Function< Double, Double > > createWeightFunctions()
	{
		return blockFactory.createWeightFunctions( this );
	}

	public P solveTypeParameters() { return solveTypeParameters; }
	public F blockFactory() { return blockFactory; }

	public ResolvedTileSpecCollection rtsc() { return rtsc; }
	public HashMap< String, R > idToNewModel() { return idToNewModel; }
	public HashMap< String, List< Pair< String, Double > > > idToBlockErrorMap() { return idToBlockErrorMap; }

	public HashMap< Integer, HashSet< String > > zToTileId() { return zToTileId; }

	public void assignUpdatedId( final int id ) { this.id = id; }

	public Worker< M, R, P, F > createWorker( final int startId, final int threadsWorker )
	{
		return solveTypeParameters().createWorker( this , startId, threadsWorker );
	}


	/**
	 * Fetches basic data for all TileSpecs
	 *
	 * @param allTileSpecs - all TileSpec objects that are part of this solve
	 * @param sectionIdToZMap - will be filled
	 * @return a Pair< minZ, maxZ >
	 */
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
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, maxZ, minZ, rtsc);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		@SuppressWarnings("rawtypes")
		BlockData other = (BlockData) obj;
		return id == other.id && maxZ == other.maxZ && minZ == other.minZ && Objects.equals(rtsc, other.rtsc);
	}
}
