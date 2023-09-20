package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang.math.IntRange;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.assembly.AssemblyMaps;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.solver.SerializableValuePair;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 * Will need to add this parameter object later rather than extending the class I think
 * 
 * @author preibischs
 *
 * @param <R> - the result
 * @param <P> - the solve parameters
 */
public class BlockData<R, P extends BlockDataSolveParameters<?, R, P>> implements Serializable
{
	private static final long serialVersionUID = -6491517262420660476L;

	private int id;

	// the BlockFactory that created this BlockData
	final private BlockFactory blockFactory;

	// contains solve-specific parameters and models
	final private P solveTypeParameters;

	// used for saving and display
	final private ResolvedTileSpecCollection rtsc;

	// all z-layers as String map to List that only contains the z-layer as double
	final protected Map<String, ArrayList<Double>> sectionIdToZMap; 

	// what z-range this block covers
	final protected int minZ, maxZ;

	//
	// below are the results that the worker has to fill up
	//
	final private AssemblyMaps<R> localData = new AssemblyMaps<>();

	// TODO: specifically collected should go into the Parameter objects? We need to make sure each has it's own instance then
	// coefficient-tile intensity average for global intensity-correction
	final HashMap<String, ArrayList<Double>> idToAverages = new HashMap<>();

	// TODO: replace BlockFactory argument by WeightFunction?
	public BlockData(
			final BlockFactory blockFactory, // knows how it was created for assembly later?
			final P solveTypeParameters,
			final int id,
			final ResolvedTileSpecCollection rtsc )
	{
		this.id = id;
		this.blockFactory = blockFactory;
		this.solveTypeParameters = solveTypeParameters;
		this.rtsc = rtsc;

		this.sectionIdToZMap = new HashMap<>();
		localData.idToTileSpec.putAll(rtsc.getTileIdToSpecMap());
		localData.sharedTransformSpecs.addAll(rtsc.getTransformSpecs());

		// TODO: trautmane
		final IntRange zRange = fetchRenderDetails( rtsc.getTileSpecs(), sectionIdToZMap );
		this.minZ = zRange.getMinimumInteger();
		this.maxZ = zRange.getMaximumInteger();
	}

	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }
	public Map<String, ArrayList<Double>> sectionIdToZMap() { return sectionIdToZMap; }

	public int getId() { return id; }
	public WeightFunction createWeightFunction() {
		return blockFactory.createWeightFunction(this);
	}

	/**
	 * @return - the center of mass of all tiles that are part of this solve. If the coordinates are changed, the current ones should be used.
	 */
	public double[] centerOfMass() { return solveTypeParameters().centerOfMass( this ); }

	/**
	 * @return - the bounding box of all tiles that are part of this solve. If the coordinates are changed, the current ones should be used.
	 */
	public Bounds boundingBox() { return solveTypeParameters().boundingBox(this); }

	public P solveTypeParameters() { return solveTypeParameters; }
	public BlockFactory blockFactory() { return blockFactory; }

	public ResolvedTileSpecCollection rtsc() { return rtsc; }
	public HashMap<String, R> idToNewModel() { return localData.idToModel; }
	public HashMap<String, List<SerializableValuePair<String, Double>>> idToBlockErrorMap() { return localData.idToErrorMap; }
	public HashMap<String, ArrayList<Double>> idToAverages() { return idToAverages; }

	public HashMap<Integer, HashSet<String>> zToTileId() { return localData.zToTileId; }

	public void assignUpdatedId( final int id ) { this.id = id; }

	public Worker<R, P> createWorker( final int startId, final int threadsWorker )
	{
		return solveTypeParameters().createWorker( this , startId, threadsWorker );
	}


	/**
	 * Fetches basic data for all TileSpecs
	 *
	 * @param allTileSpecs - all TileSpec objects that are part of this solve
	 * @param sectionIdToZMap - will be filled
	 * @return an IntRange with the min and max z values
	 */
	private static IntRange fetchRenderDetails(
			final Collection< TileSpec > allTileSpecs,
			final Map<String, ArrayList<Double>> sectionIdToZMap )
	{
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (final TileSpec t : allTileSpecs)
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

		return new IntRange(minZ, maxZ);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, maxZ, minZ, rtsc);
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final BlockData<?,?> other = (BlockData<?,?>) obj;
		return id == other.id && maxZ == other.maxZ && minZ == other.minZ && Objects.equals(rtsc, other.rtsc);
	}
}
