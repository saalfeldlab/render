package org.janelia.render.client.newsolver;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 * Will need to add this parameter object later rather than extending the class I think
 * 
 * @author preibischs
 *
 */
public class BlockData
{
	Object solveTypeParameters;

	public int getId() { return -1; }
	public int getWeight( final double location, final int dim ) { return -1; }
	public double getCenter( final int dim ) { return -1; }

	public Worker createWorker()
	{
		// should maybe ask the solveTypeParamters to create the object I think
		return null;
	}
	
}
