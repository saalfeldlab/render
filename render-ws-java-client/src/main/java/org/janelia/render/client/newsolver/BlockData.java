package org.janelia.render.client.newsolver;

import java.io.Serializable;
import java.util.Set;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.blockfactories.BlockTileBoundsFilter;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.render.client.newsolver.solvers.Worker;

/**
 * Should contain only geometric data, nothing specific to the type of solve
 *
 * @author preibischs
 *
 * @param <R> - the result
 * @param <P> - the solve parameters
 */
public class BlockData<R, P extends BlockDataSolveParameters<?, R, P>> implements Serializable
{
	private static final long serialVersionUID = -6491517262420660476L;

	/**
	 * The original bounds of this block (as assigned by its factory) which likely differs from the bounds
	 * of all tiles in the block ( see {@link #getPopulatedBounds()} ).  Blocks that get split based upon
	 * tile connectivity will have the same originalBounds, but different populatedBounds.
	 */
	private final Bounds originalBounds;

	/** Solve-specific parameters and models. */
	private final P solveTypeParameters;

	/** Identifies which tiles within this block should be processed. */
	private final BlockTileBoundsFilter blockTileBoundsFilter;

	/** Results populated by worker. */
	private final ResultContainer<R> localResults;

	public BlockData(final P solveTypeParameters,
					 final Bounds originalBounds,
					 final BlockTileBoundsFilter blockTileBoundsFilter) {
		this(originalBounds, solveTypeParameters, blockTileBoundsFilter, new ResultContainer<>());
	}

	private BlockData(final Bounds originalBounds,
					  final P solveTypeParameters,
					  final BlockTileBoundsFilter blockTileBoundsFilter,
					  final ResultContainer<R> localResults) {
		this.originalBounds = originalBounds;
		this.solveTypeParameters = solveTypeParameters;
		this.blockTileBoundsFilter = blockTileBoundsFilter;
		this.localResults = localResults;
	}

	/**
	 * @return the original bounds of this block (as assigned by its factory).
	 */
	public Bounds getOriginalBounds() {
		return originalBounds;
	}

	/**
	 * @return the smallest bounds containing the union of the bounds of all tiles within this block.
	 *         This is dynamically calculated, so call once and save if you need to use it repeatedly.
	 */
	public Bounds getPopulatedBounds() {
		return rtsc().toBounds();
	}

	public P solveTypeParameters() { return solveTypeParameters; }

	public BlockTileBoundsFilter getBlockTileBoundsFilter() {
		return blockTileBoundsFilter;
	}

	/**
	 * @return a copy of this block that only contains data for the specified tileIds.
	 */
	public BlockData<R, P> buildSplitBlock(final Set<String> withTileIds) {
		final ResultContainer<R> splitResults = this.localResults.buildSplitResult(withTileIds);
		return new BlockData<>(this.originalBounds,
							   this.solveTypeParameters,
							   this.blockTileBoundsFilter,
							   splitResults);
	}

	public ResolvedTileSpecCollection rtsc() { return localResults.getResolvedTileSpecs(); }

	public ResultContainer<R> getResults() { return localResults; }

	public Worker<R, P> createWorker(final int threadsWorker) {
		return solveTypeParameters().createWorker( this , threadsWorker );
	}
	
	@Override
	public String toString() {
		// include hash code in toString to help differentiate between split blocks in logs
		return originalBounds + "@" + Integer.toHexString(hashCode());
	}

	public String toDetailsString() {
		return "{\"hashCode\": \"" + Integer.toHexString(hashCode()) +
			   "\", \"originalBounds\": " + originalBounds +
			   "\", \"localResults\": " + localResults.toDetailsString() +
			   ", \"solveTypeParametersClass\": \"" + solveTypeParameters.getClass() + '}';
	}

}
