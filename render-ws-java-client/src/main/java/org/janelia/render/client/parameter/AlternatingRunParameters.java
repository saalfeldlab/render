package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters for alternating runs of block-wise optimizations.
 * <p>
 * Block-wise optimization is performed in parallel, but the areas where blocks
 * overlap can look bad. Therefore, iterate the solver with different block
 * layouts so that the blocks overlap in different areas.
 *
 * @author Michael Innerberger
 */
public class AlternatingRunParameters implements Serializable {
	@Parameter(
			names = "--alternatingRuns",
			description = "Number of alternating solve runs (default: 1)")
	public int nRuns = 1;

	@Parameter(
			names = "--keepIntermediateStacks",
			description = "Keep intermediate stacks (default: false)",
			arity = 0)
	public boolean keepIntermediateStacks = false;
}
