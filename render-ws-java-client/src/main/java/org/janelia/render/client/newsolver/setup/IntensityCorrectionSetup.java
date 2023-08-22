package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;


public class IntensityCorrectionSetup extends CommandLineParameters {
	private static final long serialVersionUID = -932686804562684884L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

	@ParametersDelegate
	public IntensityAdjustParameters intensityAdjust = new IntensityAdjustParameters();

	@ParametersDelegate
	public TargetStackParameters targetStack = new TargetStackParameters();

    // Parameter for testing
	@Parameter(
			names = "--visualizeResults",
			description = "Visualize results (if running interactively)",
			arity = 0)
	public boolean visualizeResults = false;

	public void initDefaultValues() {
		// owner for target is the same as owner for render, if not specified otherwise
		if ( this.targetStack.owner == null )
			this.targetStack.owner = renderWeb.owner;

		// project for target is the same as project for render, if not specified otherwise
		if ( this.targetStack.project == null )
			this.targetStack.project = renderWeb.project;
	}
}
