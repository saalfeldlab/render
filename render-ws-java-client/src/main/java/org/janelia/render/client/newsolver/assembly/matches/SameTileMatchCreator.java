package org.janelia.render.client.newsolver.assembly.matches;

import java.util.List;

import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.PointMatch;
import org.janelia.render.client.newsolver.BlockData;

public interface SameTileMatchCreator< R >
{
	/**
	 * Add a reasonable number of matches to the provided List that map the same TileSpec from one BlockData to another
	 *
	 * @param tileSpec - the TileSpec of the common TileSpec for which we have two models
	 * @param modelA - model of BlockData A, needs to be applied to p
	 * @param modelB - model of BlockData A, needs to be applied to q
	 * @param matchesAtoB - list to add the PointMatches to
	 */
	public void addMatches(
			TileSpec tileSpec,
			R modelA,
			R modelB,
			List< PointMatch > matchesAtoB );

	/**
	 * Set the block context in which the matches are created - this is required for the intensity based match creator
	 * @param blockContext - the block that is queried for context data
	 */
	void setBlockContext(BlockData<?, ?, ?, ?> blockContext);
}
