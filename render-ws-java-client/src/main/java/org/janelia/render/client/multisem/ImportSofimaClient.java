package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.transform.DisplacementFieldTransform;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a {@link DisplacementFieldTransform} to every tile spec of a stack, layer by layer.
 * <p>
 * The (dense) displacement field is currently expected to be SOFIMA output for multi-SEM acquisitions, stored as a
 * Neuroglancer precomputed volume with a 4D {@code [x,y,z,channel]} layout and one z-slice per stack layer (opened
 * through the same {@link DisplacementFieldTransform#openPrecomputedReader} path the transform itself uses). For
 * each layer, this client
 * <ul>
 *   <li>computes {@code fieldZIndex} from the layer z and the stack's {@code minZ},</li>
 *   <li>appends a {@link DisplacementFieldTransform} with the resulting data string to each tile spec, and</li>
 *   <li>saves the modified tile specs to the target stack.</li>
 * </ul>
 * Field index {@code (0,0,0)} is taken to sit on the minimum corner {@code (minX,minY,minZ)} of the source stack
 * bounds, since the field is computed on an export of that stack and an export re-origins the data at {@code (0,0,0)}.
 * The x and y parts of that corner go into the data string as the transform's offset; the z part turns the layer z
 * into the field's z-slice index. The data string's scale is either the {@code --scale} given on the command line or,
 * if that is omitted, the stack bounds divided by the field dimensions and rounded to a whole number. Only the vector
 * scale is left at its default, which suits SOFIMA output with vectors already in full-resolution units.
 * Layers are processed in order, but the tiles within a layer are processed by {@code --numThreads} threads, which is
 * what parallelizes the field chunk reads that deriving the bounding boxes triggers.
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
		@Parameter(names = "--targetStack", description = "Stack to save modified tile specs to", required = true)
		private String targetStack;
		@Parameter(names = "--sofimaFieldUri", description = "URI of the SOFIMA displacement field N5 container", required = true)
		private String sofimaFieldUri;
		@Parameter(names = "--scale", description = "Full-resolution pixels per field pixel, i.e. the factor by which the field is downsampled in x and y (e.g. 40); derived from the stack bounds and the field dimensions if omitted")
		private Double scale;
		@Parameter(names = "--completeTargetStack", description = "Complete the target stack after all layers have been saved")
		private boolean completeTargetStack = false;
		@Parameter(names = "--numThreads", description = "Number of tiles within a layer to process concurrently (default: 1)")
		private int numThreads = 1;
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
		final Bounds stackBounds = sourceStackMetaData.getStats().getStackBounds();

		// The field is computed on an export of this stack, and an export puts its own origin at (0,0,0) and merely
		// notes the world offset in its metadata - which the field producer does not read. So field index (0,0,0)
		// sits on the minimum corner of the stack bounding box, in z just as much as in x and y.
		final double[] offset = { stackBounds.getMinX(), stackBounds.getMinY(), stackBounds.getMinZ() };

		// Open the field up front so that a bad URI fails before any stack is touched, and work out the scale
		final double scale;
		try (final N5Reader fieldReader = DisplacementFieldTransform.openPrecomputedReader(params.sofimaFieldUri)) {
			// The precomputed dataset lives under the first scale key (see DisplacementFieldTransform); the
			// layout is [x,y,z,channel], so dim 0 is X and dim 1 is Y.
			final String scaleKey = fieldReader.list("/")[0];
			final long[] fieldDimensions = fieldReader.getDatasetAttributes(scaleKey).getDimensions();

			// The field does not cover the stack bounds exactly, rounding recovers the intended
			// factor; the leftover strip is handled by the transform's mirrored extension.
			final double xScale = Math.round(stackBounds.getDeltaX() / fieldDimensions[0]);
			final double yScale = Math.round(stackBounds.getDeltaY() / fieldDimensions[1]);
			if ((params.scale == null) && (xScale != yScale)) {
				// The transform downsamples x and y by the same factor, so a field that does not is not supported.
				throw new IllegalArgumentException(
						"derived x and y scales differ (" + xScale + " vs " + yScale + "); pass --scale explicitly");
			}
			scale = (params.scale != null) ? params.scale : xScale;

			LOG.info("addDisplacementField: stack bounds are {}, field {} has dimensions {}, scale is {}, offset is {}",
					 stackBounds, scaleKey, Arrays.toString(fieldDimensions), scale, Arrays.toString(offset));
		} catch (final Exception e) {
			throw new IllegalArgumentException("Failed to process SOFIMA field at " + params.sofimaFieldUri, e);
		}

		// Set up the target stack
		final String targetStack = params.targetStack;
		if (! targetStack.equals(params.stack)) {
			renderClient.setupDerivedStack(sourceStackMetaData, targetStack);
		} else {
			renderClient.ensureStackIsInLoadingState(targetStack, sourceStackMetaData);
		}

		// Get and process all z values
		final List<Double> zValues = renderClient.getStackZValues(params.stack,
																  params.zRangeParams.minZ,
																  params.zRangeParams.maxZ);
		LOG.info("addDisplacementField: processing {} layers with {} threads", zValues.size(), params.numThreads);

		// One pool, reused for the tiles of each layer in turn (see addFieldToLayer for why tiles and not layers)
		try (final ForkJoinPool pool = new ForkJoinPool(params.numThreads)) {
			for (final Double z : zValues) {
				// Derive the slice from z itself rather than from the running layer index, so that a gap in the
				// stack's z values does not shift every later layer onto the wrong slice.
				final long fieldZIndex = Math.round(z - offset[2]);
				addFieldToLayer(z, buildDataString(fieldZIndex, scale, offset), targetStack, pool);
			}
		}

		// Complete the target stack
		if (params.completeTargetStack) {
			LOG.info("addDisplacementField: completing stack {}", targetStack);
			renderClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);
		}

		LOG.info("addDisplacementField: exit");
	}

	/**
	 * Adds the transform to every tile spec of one layer and saves them. Deriving a bounding box evaluates the
	 * transform over the tile's footprint, so this is where the (blocking) field chunk reads happen. Tiles are the
	 * natural axis to parallelize: they cover disjoint parts of the field, so concurrent tiles load disjoint chunks
	 * and the round-trip latency of those reads overlaps.
	 */
	private void addFieldToLayer(final Double z,
								 final String dataString,
								 final String targetStack,
								 final ForkJoinPool pool)
			throws IOException {

		final ResolvedTileSpecCollection tileSpecs = renderClient.getResolvedTiles(params.stack, z);

		// Run the parallel stream inside the given pool. Balances the load internally
		pool.invoke(ForkJoinTask.adapt(() -> tileSpecs.getTileSpecs().parallelStream().forEach(tileSpec -> {
			final LeafTransformSpec transformSpec = new LeafTransformSpec(DisplacementFieldTransform.class.getName(), dataString);
			tileSpec.addTransformSpecs(List.of(transformSpec));
			tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
		})));

		renderClient.saveResolvedTiles(tileSpecs, targetStack, z);
		LOG.info("addFieldToLayer: saved {} tile specs for z {}", tileSpecs.getTileCount(), z);
	}

	/**
	 * Compiles the {@link DisplacementFieldTransform} data string for one layer. The format must match what
	 * {@link DisplacementFieldTransform#init(String)} parses. Only the vector scale is omitted, so that the
	 * transform's default of 1 applies (SOFIMA vectors are already in full-resolution pixels).
	 */
	private String buildDataString(final long fieldZIndex,
								   final double scale,
								   final double[] offset) {
		return params.sofimaFieldUri +
			   "?z=" + fieldZIndex +
			   "&scale=" + scale +
			   "&offset=" + offset[0] + "," + offset[1];
	}

	private static final Logger LOG = LoggerFactory.getLogger(ImportSofimaClient.class);
}
