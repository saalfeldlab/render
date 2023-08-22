package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;

import java.util.List;

public class IntensityCorrectionSetup extends CommandLineParameters {
	private static final long serialVersionUID = -932686804562684884L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

	@ParametersDelegate
	public IntensityAdjustParameters intensityAdjust = new IntensityAdjustParameters();

    //
    // for saving and running
    //

    @Parameter(
            names = "--targetOwner",
            description = "Owner name for intensity corrected result stack (default is same as owner)"
    )
    public String targetOwner;

    @Parameter(
            names = "--targetProject",
            description = "Project name for intensity corrected result stack (default is same as project)"
    )
    public String targetProject;

    @Parameter(
            names = "--targetStack",
            description = "Name for intensity corrected result stack (if omitted, models are simply logged)")
    public String targetStack;

    @Parameter(
            names = "--completeTargetStack",
            description = "Complete the target stack after processing",
            arity = 0)
    public boolean completeTargetStack = false;

    @Parameter(names = "--threadsWorker", description = "Number of threads to be used within each worker job (default:1)")
    public int threadsWorker = 1;

    @Parameter(names = "--threadsGlobal", description = "Number of threads to be used for global intensity correction (default: numProcessors/2)")
    public int threadsGlobal = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );

	@Parameter(
			names = "--visualizeResults",
			description = "Visualize results (if running interactively)",
			arity = 0)
	public boolean visualizeResults = false;

	public void initDefaultValues() {
		// owner for target is the same as owner for render, if not specified otherwise
		if ( this.targetOwner == null )
			this.targetOwner = renderWeb.owner;

		// project for target is the same as project for render, if not specified otherwise
		if ( this.targetProject == null )
			this.targetProject = renderWeb.project;
	}
}
