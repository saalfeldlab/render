package org.janelia.render.client.newsolver.setup;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.Serializable;
import java.util.function.Function;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.parameter.AlternatingRunParameters;
import org.janelia.render.client.parameter.BlockOptimizerParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.StitchingParameters;
import org.janelia.render.client.parameter.XYRangeParameters;
import org.janelia.render.client.parameter.ZRangeParameters;

public class AffineBlockSolverSetup extends CommandLineParameters
{
	private static final long serialVersionUID = 655629544594300471L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

	@ParametersDelegate
	public TargetStackParameters targetStack = new TargetStackParameters();

	@ParametersDelegate
	public XYRangeParameters xyRange = new XYRangeParameters();

	@ParametersDelegate
	public ZRangeParameters zRange = new ZRangeParameters();

	@ParametersDelegate
	public MatchCollectionParameters matches = new MatchCollectionParameters();

	@ParametersDelegate
	public BlockPartitionParameters blockPartition = new BlockPartitionParameters();

	@ParametersDelegate
	public StitchingParameters stitching = new StitchingParameters();

	@ParametersDelegate
	public BlockOptimizerParameters blockOptimizer = new BlockOptimizerParameters();

	@ParametersDelegate
	public AlternatingRunParameters alternatingRuns = new AlternatingRunParameters();

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

	@Parameter(
			names = "--stitchFirst",
			description = "if stitching per z-layer should be performed prior to block alignment (default: false)"
	)

	public boolean stitchFirst = false;

	@Parameter(
            names = "--preAlign",
            description = "Type of pre-alignment used: NONE, TRANSLATION, RIGID. Note: if you use 'stitchFirst' you must specify TRANSLATION or RIGID (default: none)"
    )
    public PreAlign preAlign = PreAlign.NONE;

    @Parameter(names = "--maxNumMatches", description = "Limit maximum number of matches in between tile pairs (default:0, no limit)")
    public int maxNumMatches = 0;

    @Parameter(names = "--maxZRangeMatches", description = "max z-range in which to load matches (default: '-1' - no limit)")
    public int maxZRangeMatches = -1;

	// TODO: remove this parameter if it remains unused after we are done with the wafer 53 alignment
    // Parameter for testing
	@SuppressWarnings("unused")
	@Parameter(
			names = "--visualizeResults",
			description = "Visualize results (if running interactively)",
			arity = 0)
	public boolean visualizeResults = false;

	public void initDefaultValues() {
		if (!blockOptimizer.isConsistent())
			throw new RuntimeException("Number of entries for blockOptimizerIterations, blockMaxPlateauWidth, blockOptimizerLambdasTranslation and blockOptimizerLambdasRigid not identical.");

		// owner for matches is the same as owner for render, if not specified otherwise
		if ( this.matches.matchOwner == null )
			this.matches.matchOwner = renderWeb.owner;

		// owner for target is the same as owner for render, if not specified otherwise
		if ( this.targetStack.owner == null )
			this.targetStack.owner = renderWeb.owner;

		// project for target is the same as project for render, if not specified otherwise
		if ( this.targetStack.project == null )
			this.targetStack.project = renderWeb.project;

	}

	public <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>> FIBSEMAlignmentParameters<M, S> setupSolveParameters(
			final M blockModel,
			final S stitchingModel) {
		return new FIBSEMAlignmentParameters<>(
				blockModel.copy(),
				(Function<Integer, S> & Serializable) z -> stitchingModel.copy(),
				null,
				new StitchingParameters(),
				blockOptimizer,
				preAlign,
				renderWeb,
				stack,
				matches,
				maxNumMatches,
				maxZRangeMatches);
	}

	public <M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>> FIBSEMAlignmentParameters<M, S> setupSolveParametersWithStitching(
			final M blockModel,
			final S stitchingModel) {
		return new FIBSEMAlignmentParameters<>(
				blockModel.copy(),
				(Function<Integer, S> & Serializable) z -> stitchingModel.copy(),
				(Function< Integer, Integer > & Serializable )(z) -> stitching.minInliers,
				stitching,
				blockOptimizer,
				preAlign,
				renderWeb,
				stack,
				matches,
				maxNumMatches,
				maxZRangeMatches);
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
	public AffineBlockSolverSetup buildPipelineClone(final String baseDataUrl,
													 final StackWithZValues stackWithZValues,
													 final boolean deriveMatchCollectionNamesFromProject,
													 final String matchSuffix) {

		final AffineBlockSolverSetup clone = clone();

		clone.renderWeb.baseDataUrl = baseDataUrl;

		final StackId sourceStackId = stackWithZValues.getStackId();
		clone.renderWeb.owner = sourceStackId.getOwner();
		clone.renderWeb.project = sourceStackId.getProject();
		clone.stack = sourceStackId.getStack();

		clone.zRange.minZ = stackWithZValues.getFirstZ();
		clone.zRange.maxZ = stackWithZValues.getLastZ();

		// TODO: should we log a warning and/or abort if the zValues have "holes" and don't cover the entire zRange?

		clone.targetStack.setValuesFromPipeline(sourceStackId, "_align");

		// if a single match collection for all stacks has not been explicitly specified,
		// derive it from project or stack name
		if (clone.matches.matchCollection == null) {
			final MatchCollectionId mc = sourceStackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject);
			clone.matches.matchCollection = mc.getName() + matchSuffix;
		}

		return clone;
	}

	/** (Slowly) creates a clone of this setup by serializing it to and from JSON. */
	@SuppressWarnings("MethodDoesntCallSuperMethod")
	public AffineBlockSolverSetup clone() {
		final String json = JSON_HELPER.toJson(this);
		return JSON_HELPER.fromJson(json);
	}

	private static final JsonUtils.Helper<AffineBlockSolverSetup> JSON_HELPER =
			new JsonUtils.Helper<>(AffineBlockSolverSetup.class);
}
