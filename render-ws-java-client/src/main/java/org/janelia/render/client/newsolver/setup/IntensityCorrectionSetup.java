package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.AlternatingRunParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.XYRangeParameters;
import org.janelia.render.client.parameter.ZRangeParameters;


@Parameters
public class IntensityCorrectionSetup extends CommandLineParameters {
	private static final long serialVersionUID = -932686804562684884L;

	@ParametersDelegate
	public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

	@ParametersDelegate
	public AlgorithmicIntensityAdjustParameters intensityAdjust = new AlgorithmicIntensityAdjustParameters();

	@ParametersDelegate
	public TargetStackParameters targetStack = new TargetStackParameters();

	@ParametersDelegate
	public XYRangeParameters xyRange = new XYRangeParameters();

	@ParametersDelegate
	public ZRangeParameters layerRange = new ZRangeParameters();

	@ParametersDelegate
	public BlockPartitionParameters blockPartition = new BlockPartitionParameters();

	@ParametersDelegate
	public AlternatingRunParameters alternatingRuns = new AlternatingRunParameters();

    // Parameter for testing
	@SuppressWarnings("unused")
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

		this.intensityAdjust.initDefaultValues();
	}

	/**
	 * @param  baseDataUrl                            base web service URL for data.
	 * @param  stackWithZValues                       identifies stack and z layers to align.
	 *
	 * @param  deriveMatchCollectionNamesFromProject  indicates whether derived match collection names
	 *                                                should be derived from the stack's project
	 *                                                (default is to derive from stack name).
	 *
	 * @param  matchSuffix                            suffix to append to derived match collection names
	 *                                                (specify empty string to omit suffix).
	 *                                                Suffix is needed when match aggregation is performed
	 *                                                by an earlier pipeline step.
	 * @return a clone of this setup populated with the specified parameters.
	 */
	public IntensityCorrectionSetup buildPipelineClone(final String baseDataUrl,
													   final StackWithZValues stackWithZValues,
													   final boolean deriveMatchCollectionNamesFromProject,
													   final String matchSuffix) {

		final IntensityCorrectionSetup clone = clone();

		clone.renderWeb.baseDataUrl = baseDataUrl;

		final StackId sourceStackId = stackWithZValues.getStackId();
		clone.renderWeb.owner = sourceStackId.getOwner();
		clone.renderWeb.project = sourceStackId.getProject();
		clone.intensityAdjust.stack = sourceStackId.getStack();

		clone.layerRange.minZ = stackWithZValues.getFirstZ();
		clone.layerRange.maxZ = stackWithZValues.getLastZ();

		// TODO: should we log a warning and/or abort if the zValues have "holes" and don't cover the entire zRange?

		clone.targetStack.setValuesFromPipeline(sourceStackId, "_ic");

		return clone;
	}

	/** (Slowly) creates a clone of this setup by serializing it to and from JSON. */
	@SuppressWarnings("MethodDoesntCallSuperMethod")
	public IntensityCorrectionSetup clone() {
		final String json = JSON_HELPER.toJson(this);
		return JSON_HELPER.fromJson(json);
	}

	private static final JsonUtils.Helper<IntensityCorrectionSetup> JSON_HELPER =
			new JsonUtils.Helper<>(IntensityCorrectionSetup.class);
}
