package org.janelia.render.client.solve;

import java.util.List;

import org.janelia.alignment.match.ModelType;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

public class Parameters extends CommandLineParameters
{
	private static final long serialVersionUID = 6845718387096692785L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--minZ",
            description = "Minimum (split) Z value for layers to be processed")
    public Double minZ;

    @Parameter(
            names = "--maxZ",
            description = "Maximum (split) Z value for layers to be processed")
    public Double maxZ;

    @Parameter(
            names = "--matchOwner",
            description = "Owner of match collection for tiles (default is owner)"
    )
    public String matchOwner;

    @Parameter(
            names = "--matchCollection",
            description = "Name of match collection for tiles",
            required = true
    )
    public String matchCollection;

    @Parameter(
            names = "--regularizerModelType",
            description = "Type of model for regularizer",
            required = true
    )
    public ModelType regularizerModelType;

    @Parameter(
            names = "--samplesPerDimension",
            description = "Samples per dimension"
    )
    public Integer samplesPerDimension = 2;

    @Parameter(
            names = "--maxAllowedError",
            description = "Max allowed error"
    )
    public Double maxAllowedError = 200.0;

    @Parameter(
            names = "--maxIterations",
            description = "Max iterations"
    )
    public Integer maxIterations = 10000;

    @Parameter(
            names = "--maxPlateauWidth",
            description = "Max allowed error"
    )
    public Integer maxPlateauWidth = 800;

    @Parameter(
            names = "--startLambda",
            description = "Starting lambda for optimizer.  " +
                          "Optimizer loops through lambdas 1.0, 0.5, 0.1. 0.01.  " +
                          "If you know your starting alignment is good, " +
                          "set this to one of the smaller values to improve performance."
    )
    public Double startLambda = 1.0;

    @Parameter(
            names = "--optimizerLambdas",
            description = "Explicit optimizer lambda values.",
            variableArity = true
    )
    public List<Double> optimizerLambdas;

    @Parameter(
            names = "--targetOwner",
            description = "Owner name for aligned result stack (default is same as owner)"
    )
    public String targetOwner;

    @Parameter(
            names = "--targetProject",
            description = "Project name for aligned result stack (default is same as project)"
    )
    public String targetProject;

    @Parameter(
            names = "--targetStack",
            description = "Name for aligned result stack (if omitted, aligned models are simply logged)")
    public String targetStack;

    @Parameter(
            names = "--mergedZ",
            description = "Z value for all aligned tiles (if omitted, original split z values are kept)"
    )
    public Double mergedZ;

    @Parameter(
            names = "--completeTargetStack",
            description = "Complete the target stack after processing",
            arity = 0)
    public boolean completeTargetStack = false;

    @Parameter(names = "--threads", description = "Number of threads to be used")
    public int numberOfThreads = 1;

    public Parameters() {
    }

    void initDefaultValues() {

        if (this.matchOwner == null) {
            this.matchOwner = renderWeb.owner;
        }

        if (this.targetOwner == null) {
            this.targetOwner = renderWeb.owner;
        }

        if (this.targetProject == null) {
            this.targetProject = renderWeb.project;
        }
    }


}
