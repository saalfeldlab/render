package org.janelia.render.client.newsolver;

import java.io.Serializable;
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
					 final Bounds factoryBounds,
					 final ResolvedTileSpecCollection rtsc) {
		this.factoryBounds = factoryBounds;
		this.populatedBounds = rtsc.toBounds();
		this.solveTypeParameters = solveTypeParameters;

		localResults = new ResultContainer<>(rtsc);
	}

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

	public BlockData<R, P> buildSplitBlock(final Set<String> withTileIds) {
		return new BlockData<>(this.solveTypeParameters,
							   this.factoryBounds,
							   rtsc().copyAndRetainTileSpecs(withTileIds));
	}

	public ResolvedTileSpecCollection rtsc() { return localResults.getResolvedTileSpecs(); }

	public ResultContainer<R> getResults() { return localResults; }

	public Worker<R, P> createWorker(final int threadsWorker) {
		return solveTypeParameters().createWorker( this , threadsWorker );
	}
	
	@Override
	public String toString() {
		// include hash code in toString so that to help differentiate between split blocks in logs
		return factoryBounds + "@" + Integer.toHexString(hashCode());
	}

	public int getTileCount() {
		return localResults.getResolvedTileSpecs().getTileCount();
	}
}
