package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.solvers.Worker;

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

	/** The bounds of this block as assigned by its factory. */
	private final Bounds factoryBounds;

	/** The smallest bounds containing the union of the bounds of all tiles within this block. */
	private final Bounds populatedBounds;

	// contains solve-specific parameters and models
	final private P solveTypeParameters;

	//
	// below are the results that the worker has to fill up
	//
	final private ResultContainer<R> localResults;

	public BlockData(final P solveTypeParameters,
					 final int id,
					 final Bounds factoryBounds,
					 final ResolvedTileSpecCollection rtsc) {
		this.id = id;
		this.factoryBounds = factoryBounds;
		this.populatedBounds = rtsc.toBounds();
		this.solveTypeParameters = solveTypeParameters;

		localResults = new ResultContainer<>(rtsc);
	}

	public int getId() { return id; }

	/**
	 * @return the bounds of this block as assigned by its factory.
	 */
	public Bounds getFactoryBounds() {
		return factoryBounds;
	}

	/**
	 * @return the smallest bounds containing the union of the bounds of all tiles within this block.
	 */
	public Bounds getPopulatedBounds() {
		return populatedBounds;
	}

	public P solveTypeParameters() { return solveTypeParameters; }

	public BlockData<R, P> buildSplitBlock(final int withId,
										   final Set<String> withTileIds) {
		return new BlockData<>(this.solveTypeParameters,
							   withId,
							   this.factoryBounds,
							   rtsc().copyAndRetainTileSpecs(withTileIds));
	}

	public ResolvedTileSpecCollection rtsc() { return localResults.getResolvedTileSpecs(); }

	public ResultContainer<R> getResults() { return localResults; }

	public void assignUpdatedId( final int id ) { this.id = id; }

	public Worker<R, P> createWorker( final int startId, final int threadsWorker )
	{
		return solveTypeParameters().createWorker( this , startId, threadsWorker );
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, factoryBounds);
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		} else if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		final BlockData<?, ?> that = (BlockData<?, ?>) obj;
		if (this.id != that.id) {
			return false;
		}
        return Objects.equals(this.factoryBounds, that.factoryBounds);
    }

	@Override
	public String toString() {
		return "{\"id:\" " + id + ", \"factoryBounds\": " + factoryBounds + '}';
	}

	public int getTileCount() {
		return localResults.getResolvedTileSpecs().getTileCount();
	}
}
