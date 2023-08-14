package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.solvers.Worker;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Model;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 * Will need to add this parameter object later rather than extending the class I think
 * 
 * @author preibischs
 *
 */
public class BlockData< M extends Model< M >, R extends CoordinateTransform, P extends BlockDataSolveParameters< M, P >, F extends BlockFactory< F > > implements Serializable
{
	private static final long serialVersionUID = -6491517262420660476L;

	private int id;
	private List< Function< Double, Double > > weightF;

	// the BlockFactory that created this BlockData
	final F blockFactory;

	// contains solve-specific parameters and models
	final P solveTypeParameters;

	// used for global solve outside
	final private HashSet<String> allTileIds;

	// used for saving and display
	final private Map<String, TileSpec> idToTileSpec;

	// used for global solve outside
	final private HashMap<Integer, HashSet<String> > zToTileId = new HashMap<>();

	// contains the model as determined by the local solve
	final private HashMap<String, R> idToNewModel = new HashMap<>();

	// the errors per tile
	final HashMap< String, List< Pair< String, Double > > > idToSolveItemErrorMap = new HashMap<>();

	// what z-range this block covers
	final protected int minZ, maxZ;

	// all z-layers as String map to List that only contains the z-layer as double
	final protected Map<String, ArrayList<Double>> sectionIdToZMap; 

	public BlockData(
			final F blockFactory, // knows how it was created for assembly later?
			final P solveTypeParameters,
			final int id,
			final List< Function< Double, Double > > weightF,
			final Collection<String> allTileIds,
			final Map<String, TileSpec> idToTileSpec )
	{
		this.id = id;
		this.blockFactory = blockFactory;
		this.solveTypeParameters = solveTypeParameters;
		this.allTileIds = new HashSet<>( allTileIds );
		this.idToTileSpec = idToTileSpec;
		this.weightF = weightF;

		this.sectionIdToZMap = new HashMap<>();
		final Pair<Integer, Integer> minmax = fetchRenderDetails( idToTileSpec().values(), sectionIdToZMap );

		this.minZ = minmax.getA();
		this.maxZ = minmax.getB();
	}

	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }
	public Map<String, ArrayList<Double>> sectionIdToZMap() { return sectionIdToZMap; }

	public int getId() { return id; }
	public double getWeight( final double location, final int dim ) { return weightF.get( dim ).apply( location ); }

	public P solveTypeParameters() { return solveTypeParameters; }
	public F blockFactory() { return blockFactory; }

	public Map<String, TileSpec> idToTileSpec() { return idToTileSpec; }
	public HashSet<String> allTileIds() { return allTileIds; }
	public HashMap<String, R> idToNewModel() { return idToNewModel; }
	public HashMap< String, List< Pair< String, Double > > > idToSolveItemErrorMap() { return idToSolveItemErrorMap; }

	public HashMap<Integer, HashSet<String>> zToTileId() { return zToTileId; }

	public List< Function< Double, Double > > weightFunctions() { return weightF; }

	public Worker< M, R, P, F > createWorker()
	{
		// should maybe ask the solveTypeParamters to create the object I think
		return null;
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

		//final private HashSet<String> allTileIds;
		//final private Map<String, MinimalTileSpec> idToTileSpec;

		// tileId > String (this is the pId, qId)
		// sectionId > String (this is the pGroupId, qGroupId)
		
		//TileSpec ts = blockData.idToTileSpec().values().iterator().next();
		//String sectionId = ts.getLayout().getSectionId(); // 

	}
}
