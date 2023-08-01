package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.solver.MinimalTileSpec;

import mpicbg.models.Model;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 * Will need to add this parameter object later rather than extending the class I think
 * 
 * @author preibischs
 *
 */
public class BlockData< M extends Model< M > > implements Serializable
{
	private static final long serialVersionUID = -6491517262420660476L;

	private int id;

	// the BlockFactory that created this BlockData
	final BlockFactory factory;

	// contains solve-specific parameters and models
	final BlockDataSolveParameters<M> solveTypeParameters;

	// used for global solve outside
	final private HashSet<String> allTileIds;

	// used for saving and display
	final private Map<String, MinimalTileSpec> idToTileSpec;

	// contains the model as determined by the local solve
	final private HashMap<String, M> idToNewModel = new HashMap<>();

	public BlockData(
			final BlockFactory factory, // knows how it was created for assembly later?
			final BlockDataSolveParameters<M> solveTypeParameters,
			final int id,
			final Collection<String> allTileIds,
			final Map<String, MinimalTileSpec> idToTileSpec )
	{
		this.id = id;
		this.factory = factory;
		this.solveTypeParameters = solveTypeParameters;
		this.allTileIds = new HashSet<>( allTileIds );
		this.idToTileSpec = idToTileSpec;
	}

	public int getId() { return id; }
	public int getWeight( final double location, final int dim ) { return -1; }
	public double getCenter( final int dim ) { return -1; }

	public Map<String, MinimalTileSpec> idToTileSpec() { return idToTileSpec; }
	public HashSet<String> allTileIds() { return allTileIds; }
	public HashMap<String, M> idToNewModel() { return idToNewModel; }

	public Worker createWorker()
	{
		// should maybe ask the solveTypeParamters to create the object I think
		return null;
	}
	
}
