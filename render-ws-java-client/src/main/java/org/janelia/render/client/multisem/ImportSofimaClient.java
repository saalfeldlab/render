package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.transform.DisplacementFieldTransform;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a {@link DisplacementFieldTransform} to every tile spec of a stack, layer by layer.
 * <p>
 * The (dense) displacement field is currently expected to be SOFIMA output for multi-SEM acquisitions, stored as a
 * 4D {@code [X,Y,C,Z]} N5 dataset with one z-slice per stack layer. For each layer, this client
 * <ul>
 *   <li>computes {@code fieldZIndex} from the {@code --zOffset} parameter and the running (0-based) layer index,</li>
 *   <li>computes the field-to-full-resolution {@code xyScale} from the stack bounds and the field's XY dimensions,</li>
 *   <li>appends a {@link DisplacementFieldTransform} with the resulting data string to each tile spec, and</li>
 *   <li>saves the modified tile specs to the target stack.</li>
 * </ul>
 * If {@code --targetStack} is given, the modified tile specs are written there (the stack is derived from the source
 * if it does not yet exist); otherwise they are written back into the source stack. If {@code --completeTargetStack}
 * is set, the target stack is completed once all layers have been saved.
 *
 * @author Michael Innerberger
 */
public class ImportSofimaClient {

	private final Parameters params;
	private final RenderDataClient renderClient;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final ZRangeParameters zRangeParams = new ZRangeParameters();
		@Parameter(names = "--stack", description = "Source stack to which the displacement field is added", required = true)
		private String stack;
		@Parameter(names = "--targetStack", description = "Stack to save modified tile specs to (defaults to the source stack)")
		private String targetStack;
		@Parameter(names = "--sofimaFieldUri", description = "URI of the SOFIMA displacement field N5 container", required = true)
		private String sofimaFieldUri;
		@Parameter(names = "--sofimaScaleIndex", description = "Scale index at which the deformed images were fed to SOFIMA (used to adjust vector sizes)", required = true)
		private int sofimaScaleIndex;
		@Parameter(names = "--zOffset", description = "Offset added to the running (0-based) layer index to obtain the field's z-slice index (default: 0)")
		private int zOffset = 0;
		@Parameter(names = "--completeTargetStack", description = "Complete the target stack after all layers have been saved")
		private boolean completeTargetStack = false;

		public String getTargetStack() {
			return (targetStack == null) ? stack : targetStack;
		}
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {
				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final ImportSofimaClient client = new ImportSofimaClient(parameters);
				client.addDisplacementField();
			}
		};
		clientRunner.run();
	}

	public ImportSofimaClient(final Parameters parameters) {
		this.params = parameters;
		this.renderClient = new RenderDataClient(parameters.renderParams.baseDataUrl,
												 parameters.renderParams.owner,
												 parameters.renderParams.project);
	}

	public void addDisplacementField() throws Exception {

		final StackMetaData sourceStackMetaData = renderClient.getStackMetaData(params.stack);

		// Get full-resolution stack size and the field's XY size to scale the field to full resolution
		final double[] xyScale;
		try (final N5Reader fieldReader = new N5Factory().openReader(StorageFormat.N5, params.sofimaFieldUri)) {

			final Bounds stackBounds = sourceStackMetaData.getStats().getStackBounds();
			final long[] fieldDimensions = fieldReader.getDatasetAttributes("/").getDimensions();

			xyScale = new double[]{
					stackBounds.getDeltaX() / fieldDimensions[0],
					stackBounds.getDeltaY() / fieldDimensions[1]
			};
			LOG.info("addDisplacementField: stack bounds are {}, field bounds are {}", stackBounds, Arrays.toString(fieldDimensions));
			LOG.info("addDisplacementField: xy scales are {}", Arrays.toString(xyScale));
		} catch (final Exception e) {
			throw new IllegalArgumentException("Failed to process SOFIMA field at " + params.sofimaFieldUri, e);
		}

		// Set up the target stack
		final String targetStack = params.getTargetStack();
		if (! targetStack.equals(params.stack)) {
			renderClient.setupDerivedStack(sourceStackMetaData, targetStack);
		} else {
			renderClient.ensureStackIsInLoadingState(targetStack, sourceStackMetaData);
		}

		// Get and process all z values
		final List<Double> zValues = renderClient.getStackZValues(params.stack,
																  params.zRangeParams.minZ,
																  params.zRangeParams.maxZ);
		LOG.info("addDisplacementField: processing {} layers", zValues.size());

		for (int layerIndex = 0; layerIndex < zValues.size(); layerIndex++) {
			final Double z = zValues.get(layerIndex);
			final int fieldZIndex = params.zOffset + layerIndex;
			final String dataString = buildDataString(fieldZIndex, xyScale);

			addFieldToLayer(z, dataString, targetStack);
		}

		// Complete the target stack
		if (params.completeTargetStack) {
			LOG.info("addDisplacementField: completing stack {}", targetStack);
			renderClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);
		}

		LOG.info("addDisplacementField: exit");
	}

	private void addFieldToLayer(final Double z,
								 final String dataString,
								 final String targetStack)
			throws IOException {

		final ResolvedTileSpecCollection tileSpecs = renderClient.getResolvedTiles(params.stack, z);

		for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
			final LeafTransformSpec transformSpec =
					new LeafTransformSpec(DisplacementFieldTransform.class.getName(), dataString);
			tileSpec.addTransformSpecs(List.of(transformSpec));
			tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
		}

		renderClient.saveResolvedTiles(tileSpecs, targetStack, z);
	}

	/**
	 * Compiles the {@link DisplacementFieldTransform} data string for one layer. The format must match what
	 * {@link DisplacementFieldTransform#init(String)} parses (and {@link DisplacementFieldTransform#toDataString()}
	 * produces).
	 */
	private String buildDataString(final int fieldZIndex,
								   final double[] xyScale) {
		return params.sofimaFieldUri +
			   "?scaleIndex=" + params.sofimaScaleIndex +
			   "&zIndex=" + fieldZIndex +
			   "&scaleX=" + xyScale[0] +
			   "&scaleY=" + xyScale[1];
	}

	private static final Logger LOG = LoggerFactory.getLogger(ImportSofimaClient.class);
}
