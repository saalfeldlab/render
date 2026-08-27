package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.List;

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
	 * @param  baseDataUrl       base web service URL for data.
	 * @param  stackWithZValues  identifies stack and z layers to align.
	 *
	 * @return a clone of this setup populated with the specified parameters.
	 */
	public IntensityCorrectionSetup buildPipelineClone(final String baseDataUrl,
													   final StackWithZValues stackWithZValues) {

		final IntensityCorrectionSetup clone = clone();

		clone.renderWeb.baseDataUrl = baseDataUrl;

		final StackId sourceStackId = stackWithZValues.getStackId();
		clone.renderWeb.owner = sourceStackId.getOwner();
		clone.renderWeb.project = sourceStackId.getProject();
		clone.intensityAdjust.stack = sourceStackId.getStack();

		clone.layerRange.minZ = ((layerRange != null) && (layerRange.minZ != null)) ? layerRange.minZ : stackWithZValues.getFirstZ();
		clone.layerRange.maxZ = ((layerRange != null) && (layerRange.maxZ != null)) ? layerRange.maxZ : stackWithZValues.getLastZ();

		// TODO: should we log a warning and/or abort if the zValues have "holes" and don't cover the entire zRange?

		clone.targetStack.setValuesFromPipeline(sourceStackId, "_ic");

		return clone;
	}

	/**
	 * Builds a separate clone of this setup for each z layer in the specified stack so that
	 * the layers can be corrected independently (and therefore concurrently).
	 * <p>
	 * This is only valid for 2D corrections without XY partitioning
	 * (see {@link #is2DCorrectionWithoutXYPartitioning}) because layers in those runs
	 * are not constrained by data in other layers or blocks.
	 * <p>
	 * Target stack completion is disabled in each clone so that each target stack can be completed
	 * once after all of its layers have been saved.
	 *
	 * @param  baseDataUrl       base web service URL for data.
	 * @param  stackWithZValues  identifies stack and z layers to correct.
	 *
	 * @return list with one clone of this setup for each z layer to correct.
	 */
	public List<IntensityCorrectionSetup> buildPipelineClonesForEachZ(final String baseDataUrl,
																	  final StackWithZValues stackWithZValues) {

		final List<IntensityCorrectionSetup> cloneList = new ArrayList<>();

		for (final StackWithZValues stackWithOneZ : stackWithZValues.splitByZ()) {

			final Double z = stackWithOneZ.getFirstZ();

			if (isLayerInRange(z)) {
				final IntensityCorrectionSetup clone = buildPipelineClone(baseDataUrl, stackWithOneZ);
				clone.layerRange.minZ = z;
				clone.layerRange.maxZ = z;
				clone.targetStack.completeStack = false;
				cloneList.add(clone);
			}
		}

		return cloneList;
	}

	/**
	 * @return true if the specified z is within this setup's configured layer range, otherwise false.
	 */
	private boolean isLayerInRange(final Double z) {
		return (layerRange == null) ||
			   (((layerRange.minZ == null) || (z >= layerRange.minZ)) &&
				((layerRange.maxZ == null) || (z <= layerRange.maxZ)));
	}

	/** (Slowly) creates a clone of this setup by serializing it to and from JSON. */
	@SuppressWarnings("MethodDoesntCallSuperMethod")
	public IntensityCorrectionSetup clone() {
		final String json = JSON_HELPER.toJson(this);
		return JSON_HELPER.fromJson(json);
	}

	public boolean is2DCorrectionWithoutXYPartitioning() {
		return (intensityAdjust.zDistance.getMaxZDistance() == 0) &&
			   (! blockPartition.hasXY()) &&
			   (alternatingRuns.nRuns == 1);
	}

	private static final JsonUtils.Helper<IntensityCorrectionSetup> JSON_HELPER =
			new JsonUtils.Helper<>(IntensityCorrectionSetup.class);
}
