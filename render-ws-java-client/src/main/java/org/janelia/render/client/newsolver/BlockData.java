package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;

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

	// contains solve-specific paramters and models
	final BlockDataSolveParameters<M> solveTypeParameters;

	// used for global solve outside
	final private HashSet<String> zToTileId = new HashSet<>();

	// used for saving and display
	final private HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<>();

		// contains the model as determined by the local solve
	final private HashMap<String, M> idToNewModel = new HashMap<>();

	public BlockData(
			final BlockFactory factory, // knows how it was created for assembly later?
			final BlockDataSolveParameters<M> solveTypeParameters,
			final int id )
	{
		this.id = id;
		this.solveTypeParameters = solveTypeParameters;
	}

	public int getId() { return id; }
	public int getWeight( final double location, final int dim ) { return -1; }
	public double getCenter( final int dim ) { return -1; }


	public Worker createWorker()
	{
		// should maybe ask the solveTypeParamters to create the object I think
		return null;
	}
	
}
