package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.solver.SerializableValuePair;

import java.util.List;

public class IntensityCorrectionSetup extends CommandLineParameters {
	private static final long serialVersionUID = -932686804562684884L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

	@Parameter(
			names = "--stack",
			description = "Stack name",
			required = true)
	public String stack;

	@ParametersDelegate
	public ZRangeParameters layerRange = new ZRangeParameters();

	@Parameter(
			names = "--z",
			description = "Explicit z values for sections to be processed",
			variableArity = true) // e.g. --z 20.0 21.0 22.0
	public List<Double> zValues;

	@Parameter(
			names = "--numThreads",
			description = "Number of threads to use")
	public Integer numThreads = 1;

	@Parameter(
			names = "--lambdaTranslation",
			description = "Lambda for regularization with translation model")
	public Double lambdaTranslation = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

	@Parameter(
			names = "--lambdaIdentity",
			description = "Lambda for regularization with identity model")
	public Double lambdaIdentity = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

	@Parameter(
			names = { "--maxPixelCacheGb" },
			description = "Maximum number of gigabytes of pixels to cache"
	)
	public Integer maxPixelCacheGb = 1;

	@Parameter(
			names = "--renderScale",
			description = "Scale for rendered tiles used during intensity comparison")
	public double renderScale = 0.1;

	@Parameter(
			names = "--zDistance",
			description = "If specified, apply correction across this many z-layers from the current z-layer " +
					"(omit to only correct in 2D)")
	public Integer zDistance;

	@Parameter(
			names = { "--numCoefficients" },
			description = "Number of correction coefficients to derive in each dimension " +
					"(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
	)
	public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

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
