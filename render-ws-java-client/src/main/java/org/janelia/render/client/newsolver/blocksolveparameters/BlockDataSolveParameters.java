package org.janelia.render.client.newsolver.blocksolveparameters;

import java.awt.Rectangle;
import java.io.Serializable;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.Worker;

/**
 * 
 * @author preibischs
 *
 * @param <M> - the result model type
 * @param <R> - the result type
 * @param <P> - the concrete parameter type
 */
public abstract class BlockDataSolveParameters< M, R, P extends BlockDataSolveParameters< M, R, P > > implements Serializable
{
	private static final long serialVersionUID = -813404780882760053L;

	final String baseDataUrl;
	final String owner;
	final String project;
	final String stack;

	final private M blockSolveModel;

	public BlockDataSolveParameters(
			final String baseDataUrl,
			final String owner,
			final String project,
			final String stack,
			final M blockSolveModel)
	{
		this.baseDataUrl = baseDataUrl;
		this.owner = owner;
		this.project = project;
		this.stack = stack;
		this.blockSolveModel = blockSolveModel;
	}

	public String baseDataUrl() { return baseDataUrl; }
	public String owner() { return owner; }
	public String project() { return project; }
	public String stack() { return stack; }

	public M blockSolveModel() { return blockSolveModel; }

	/**
	 * @return - the bounding box of all tiles that are part of this solve. If the coordinates are changed, the current ones should be used.
	 */
	public Bounds boundingBox(final BlockData<R, P> blockData)
	{
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;

		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;

		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;

		for ( final TileSpec ts : blockData.rtsc().getTileSpecs() )
		{
			minX = Math.min( minX, ts.getMinX() );
			minY = Math.min( minY, ts.getMinY() );
			minZ = Math.min( minZ, ts.getZ() );

			maxX = Math.max( maxX, ts.getMaxX() );
			maxY = Math.max( maxY, ts.getMaxY() );
			maxZ = Math.max( maxZ, ts.getZ() );
		}

		return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * @return - the center of mass of all tiles that are part of this solve. If the coordinates are changed, the current ones should be used.
	 */
	public double[] centerOfMass(final BlockData<R, P> blockData)
	{

		final double[] c = new double[3];
		int count = 0;

		for (final String tileId : blockData.getResults().getIdToModel().keySet()) {
			final TileSpec ts = blockData.rtsc().getTileSpec(tileId);

			// the affine transform for the tile
			final Rectangle r = ts.toTileBounds().toRectangle();
			c[0] += r.getCenterX();
			c[1] += r.getCenterY();
			c[2] += ts.getZ();
			++count;
		}

		c[0] /= count;
		c[1] /= count;
		c[2] /= count;

		return c;
	}

	public abstract Worker<R, P> createWorker(
			final BlockData<R, P> blockData,
			final int startId,
			final int threadsWorker);
}
