package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.zarr.ZarrDatasetAttributes;
import org.janelia.saalfeldlab.n5.zarr.ZarrKeyValueReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that applies the pre-computed degree-0 (spatially flat) beam-homogenization correction
 * for a Multi-SEM stack.
 * <p>
 * The correction parameters are read from a multi-SEM acquisition zarr container (the "xlog",
 * e.g. xlog_wafer_61.zarr). The {@code beam_homogenization} array has dimensions
 * {@code [scan, slab, sfov, homogenization_parameter]}; the parameter dimension (length 26) is laid out
 * (per the data author's code, JaneliaSciComp/EM_recon_pipeline PR #186) as:
 * <ul>
 *     <li>indices 0-20: degree-5 polynomial surface coefficients (21)</li>
 *     <li>index 21: the beam gain</li>
 *     <li>index 22: the degree-0 flat level</li>
 *     <li>indices 23-25: the degree-1 coefficients (3)</li>
 * </ul>
 * The author's correction formula is {@code corrected = clip(referenceLevel + gain * (image - surface), 0, 255)},
 * where {@code referenceLevel} is the {@code b_ref} attribute of the {@code beam_homogenization} array.
 * For the degree-0 correction the surface is the flat constant {@code degree_0}, so the correction reduces to
 * the per-tile affine map {@code corrected = gain * image + (referenceLevel - gain * degree_0)}. This is
 * applied by attaching a 1x1 {@link LinearIntensityMap8BitFilter} (slope {@code gain},
 * offset {@code referenceLevel - gain * degree_0}) to each tile spec; the filtered specs are written to a
 * new derived stack and the original images are not modified.
 * <p>
 * For each tile, the (scan, slab, sfov) <b>labels</b> are derived from the tile id
 * (e.g. {@code w61_magc0145_scan015_m0009_r70_s90} &rarr; slab/magc=145, scan=15, sfov=90; the
 * {@code slab} dimension is labeled by MagC id) and matched against the {@code scan}, {@code slab}, and
 * {@code sfov} coordinate arrays in the container to find the corresponding array positions (slab/magc is
 * not contiguous, so label != position). The supplied zarr must therefore contain those coordinate arrays;
 * a container holding only {@code beam_homogenization} is not sufficient.
 *
 * @author Michael Innerberger
 */
public class ThomasCalibrationIntensityCorrectionClient {

	public static class Parameters extends CommandLineParameters {

		@ParametersDelegate
		public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

		@ParametersDelegate
		public ZRangeParameters layerRange = new ZRangeParameters();

		@Parameter(names = "--stack", description = "Source stack to correct", required = true)
		public String stack;

		@Parameter(names = "--targetStack", description = "Name of the derived stack to store the corrected " +
				"tile specs (defaults to <stack>_ic)")
		public String targetStack;

		@Parameter(names = "--zarrPath", description = "Path to the multi-SEM acquisition zarr (xlog) container " +
				"holding the intensity correction parameters and the scan/slab/sfov coordinate arrays " +
				"(e.g. /path/to/xlog_wafer_61.zarr)", required = true)
		public String zarrPath;

		@Parameter(names = "--homogenizationDataset", description = "Name of the 4D correction-parameter array " +
				"within the zarr container")
		public String homogenizationDataset = "beam_homogenization";

		@Parameter(names = "--scanDataset", description = "Name of the 1D scan coordinate array")
		public String scanDataset = "scan";

		@Parameter(names = "--slabDataset", description = "Name of the 1D slab coordinate array " +
				"(labeled by the magc number of each tile)")
		public String slabDataset = "slab";

		@Parameter(names = "--sfovDataset", description = "Name of the 1D sfov coordinate array")
		public String sfovDataset = "sfov";

		@Parameter(names = "--serialDataset", description = "Name of the 1D serial-section coordinate array " +
				"(only used to cross-check / log the slab selection)")
		public String serialDataset = "id_serial";

		@Parameter(names = "--gainIndex", description = "Index of the gain parameter within the " +
				"homogenization_parameter dimension")
		public int gainIndex = 21;

		@Parameter(names = "--deg0Index", description = "Index of the degree-0 flat-level parameter within the " +
				"homogenization_parameter dimension")
		public int deg0Index = 22;

		@Parameter(names = "--referenceLevel", description = "Reference intensity level to map to " +
				"(defaults to the 'b_ref' attribute of the homogenization array)")
		public Double referenceLevel;

		@Parameter(names = "--inverted", description = "Indicates that the source images are intensity-inverted " +
				"(in' = 255 - in) relative to the data the homogenization parameters were computed for. When set, " +
				"the correction is applied in the original (non-inverted) domain and the result is re-inverted, so " +
				"the slope stays gain but the offset becomes 255*(1 - gain) - (referenceLevel - gain*degree0).", arity = 0)
		public boolean inverted = false;

		@Parameter(names = "--sfovLabelOffset", description = "Offset added to the sFOV number parsed from the " +
				"tile id to obtain the xlog sfov coordinate label. Render tile ids are 1-based (s01..s91) while " +
				"the xlog sfov coordinate is 0-based (0..90), so the default is -1.")
		public int sfovLabelOffset = -1;

		@Parameter(names = "--z", description = "Explicit z values for sections to be processed", variableArity = true)
		public List<Double> zValues;

		@Parameter(names = "--completeStack", description = "Complete the target stack after processing", arity = 0)
		public boolean completeStack = false;

		public String getTargetStack() {
			return (targetStack == null || targetStack.isEmpty()) ? stack + "_ic" : targetStack;
		}
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {
				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final ThomasCalibrationIntensityCorrectionClient client = new ThomasCalibrationIntensityCorrectionClient(parameters);
				client.correctStack();
			}
		};
		clientRunner.run();
	}

	// matches e.g. w61_magc0145_scan015_m0009_r70_s90
	private static final Pattern MAGC_PATTERN = Pattern.compile("magc(\\d+)");
	private static final Pattern SCAN_PATTERN = Pattern.compile("scan(\\d+)");
	private static final Pattern SFOV_PATTERN = Pattern.compile("_s(\\d+)(?:$|_)");

	private static final double IMAGE_MAX = 255.0;
	private static final String B_REF_KEY = "b_ref";

	private final Parameters params;

	public ThomasCalibrationIntensityCorrectionClient(final Parameters params) {
		this.params = params;
	}

	public void correctStack() throws IOException {

		final RenderDataClient dataClient = params.renderWeb.getDataClient();
		final String targetStack = params.getTargetStack();

		try (final N5Reader reader = new N5Factory().openReader(params.zarrPath)) {
			LOG.info("correctStack: opened {} using {}", params.zarrPath, reader.getClass().getSimpleName());

			final double referenceLevel = resolveReferenceLevel();

			// coordinate-value (label) -> array-position lookups
			final Map<Integer, Integer> scanLabelToPosition = readCoordinateIndex(reader, params.scanDataset);
			final Map<Integer, Integer> slabLabelToPosition = readCoordinateIndex(reader, params.slabDataset);
			final Map<Integer, Integer> sfovLabelToPosition = readCoordinateIndex(reader, params.sfovDataset);
			final int[] serialBySlabPosition = readOptionalIntArray(reader, params.serialDataset);

			LOG.info("correctStack: reference level (b_ref) is {}, source data inverted is {}; coordinate arrays loaded - {} scans, {} slabs, {} sfovs",
					 referenceLevel, params.inverted, scanLabelToPosition.size(), slabLabelToPosition.size(), sfovLabelToPosition.size());

			// the 4D correction array; axes are matched to coordinate sizes so the code is robust to axis order
			final RandomAccessibleInterval<? extends RealType<?>> homogenization = openHomogenizationArray(reader, params.homogenizationDataset);
			final int scanAxis = findAxisForSize(homogenization, scanLabelToPosition.size(), "scan");
			final int slabAxis = findAxisForSize(homogenization, slabLabelToPosition.size(), "slab");
			final int sfovAxis = findAxisForSize(homogenization, sfovLabelToPosition.size(), "sfov");
			final int parameterAxis = remainingAxis(homogenization, scanAxis, slabAxis, sfovAxis);
			final long parameterCount = homogenization.dimension(parameterAxis);

			if (params.gainIndex < 0 || params.gainIndex >= parameterCount
					|| params.deg0Index < 0 || params.deg0Index >= parameterCount) {
				throw new IllegalArgumentException("gainIndex " + params.gainIndex + " and deg0Index " + params.deg0Index +
						" must both be within the parameter dimension of size " + parameterCount);
			}

			LOG.info("correctStack: '{}' axis mapping is scan={}, slab={}, sfov={}, parameter={} (size {}); using gainIndex={}, deg0Index={}",
					 params.homogenizationDataset, scanAxis, slabAxis, sfovAxis, parameterAxis, parameterCount,
					 params.gainIndex, params.deg0Index);

			final RandomAccess<? extends RealType<?>> access = homogenization.randomAccess();
			final long[] position = new long[homogenization.numDimensions()];

			final List<Double> zValues = dataClient.getStackZValues(params.stack,
																	 params.layerRange.minZ,
																	 params.layerRange.maxZ,
																	 params.zValues);
			if (zValues.isEmpty()) {
				throw new IllegalArgumentException("source stack " + params.stack + " does not contain any matching z values");
			}

			final StackMetaData sourceStackMetaData = dataClient.getStackMetaData(params.stack);
			dataClient.setupDerivedStack(sourceStackMetaData, targetStack);

			int correctedCount = 0;
			int skippedCount = 0;

			for (final Double z : zValues) {
				final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(params.stack, z);

				for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
					final String tileId = tileSpec.getTileId();

					final int magc = parseValue(MAGC_PATTERN, tileId, "magc");
					final int scan = parseValue(SCAN_PATTERN, tileId, "scan");
					final int sfov = parseValue(SFOV_PATTERN, tileId, "sfov");
					final int sfovLabel = sfov + params.sfovLabelOffset;

					final Integer scanPosition = scanLabelToPosition.get(scan);
					final Integer slabPosition = slabLabelToPosition.get(magc);
					final Integer sfovPosition = sfovLabelToPosition.get(sfovLabel);

					if (scanPosition == null || slabPosition == null || sfovPosition == null) {
						LOG.warn("correctStack: skipping tile {} - no correction coordinate for scan={} (pos {}), magc/slab={} (pos {}), sfov={} -> label {} (pos {})",
								 tileId, scan, scanPosition, magc, slabPosition, sfov, sfovLabel, sfovPosition);
						skippedCount++;
						continue;
					}

					position[scanAxis] = scanPosition;
					position[slabAxis] = slabPosition;
					position[sfovAxis] = sfovPosition;

					position[parameterAxis] = params.gainIndex;
					access.setPosition(position);
					final double gain = access.get().getRealDouble();

					position[parameterAxis] = params.deg0Index;
					access.setPosition(position);
					final double degree0 = access.get().getRealDouble();

					if (Double.isNaN(gain) || Double.isNaN(degree0)) {
						// matches the author's behavior of leaving beams with no (NaN) correction untouched
						LOG.warn("correctStack: skipping tile {} - no correction (gain={}, degree0={}) at scan={}, slab={}, sfov={}",
								 tileId, gain, degree0, scan, magc, sfov);
						skippedCount++;
						continue;
					}

					if (correctedCount == 0) {
						final String serialInfo = (serialBySlabPosition != null && slabPosition < serialBySlabPosition.length)
								? String.valueOf(serialBySlabPosition[slabPosition]) : "n/a";
						LOG.info("correctStack: first correction - tile {} maps to scan pos {}, slab pos {} (id_serial={}), sfov pos {}; gain={}, degree0={}",
								 tileId, scanPosition, slabPosition, serialInfo, sfovPosition, gain, degree0);
					}

					tileSpec.setFilterSpec(buildHomogenizationFilterSpec(gain, degree0, referenceLevel, params.inverted));
					tileSpec.convertSingleChannelSpecToLegacyForm();
					correctedCount++;
				}

				dataClient.saveResolvedTiles(resolvedTiles, targetStack, z);
				LOG.info("correctStack: saved z {} to {}", z, targetStack);
			}

			if (params.completeStack) {
				dataClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);
			}

			LOG.info("correctStack: exit, applied degree-0 homogenization to {} tiles and skipped {} tiles across {} layers of {} (target stack {})",
					 correctedCount, skippedCount, zValues.size(), params.stack, targetStack);
		}
	}

	/**
	 * Builds a filter spec for a 1x1 (whole-tile constant) linear intensity map that implements the
	 * author's degree-0 homogenization {@code out = referenceLevel + gain * (in - degree0)}, i.e. the
	 * affine map {@code out = gain * in + (referenceLevel - gain * degree0)}.
	 * <p>
	 * When {@code inverted} is set, the source images are intensity-inverted ({@code in' = 255 - in})
	 * relative to the data the parameters were computed for. Applying the correction in the original
	 * domain and re-inverting the result ({@code out' = 255 - out}) gives
	 * {@code out' = gain * in' + (255 * (1 - gain) - (referenceLevel - gain * degree0))}: the slope is
	 * unchanged, but the offset becomes {@code 255 * (1 - gain)} minus the original offset.
	 */
	private static FilterSpec buildHomogenizationFilterSpec(final double gain,
														   final double degree0,
														   final double referenceLevel,
														   final boolean inverted) {
		// LinearIntensityMap8BitFilter applies out = a * in + 255 * b over the [0, 255] range.
		final double a = gain;
		final double offset = referenceLevel - gain * degree0;
		final double b = inverted ? (IMAGE_MAX * (1.0 - gain) - offset) / IMAGE_MAX
								  : offset / IMAGE_MAX;
		final double[][] coefficients = { { a, b } };
		final LinearIntensityMap8BitFilter filter = new LinearIntensityMap8BitFilter(1, 1, 2, coefficients);
		return FilterSpec.forFilter(filter);
	}

	private double resolveReferenceLevel() {
		if (params.referenceLevel != null) {
			return params.referenceLevel;
		}
		// NOTE: n5-zarr's reader.getAttribute(...) throws an NPE on blosc-compressed arrays because it
		// serializes the compressor, so read the b_ref value directly from the on-disk JSON metadata.
		Double bRef = readDoubleFromJsonFile(Paths.get(params.zarrPath, params.homogenizationDataset, ".zattrs"),
											 B_REF_KEY);
		if (bRef == null) {
			bRef = readBRefFromConsolidatedMetadata();
		}
		if (bRef == null) {
			throw new IllegalArgumentException("could not read '" + B_REF_KEY + "' from the " +
					params.homogenizationDataset + " metadata under " + params.zarrPath +
					"; specify --referenceLevel explicitly");
		}
		LOG.info("resolveReferenceLevel: using reference level {}={} from zarr metadata", B_REF_KEY, bRef);
		return bRef;
	}

	private Double readBRefFromConsolidatedMetadata() {
		try {
			final Path zMetadata = Paths.get(params.zarrPath, ".zmetadata");
			if (!Files.isRegularFile(zMetadata)) {
				return null;
			}
			final JsonObject root = JsonParser.parseString(Files.readString(zMetadata)).getAsJsonObject();
			final JsonObject metadata = root.getAsJsonObject("metadata");
			if (metadata == null) {
				return null;
			}
			final JsonObject attrs = metadata.getAsJsonObject(params.homogenizationDataset + "/.zattrs");
			return (attrs != null && attrs.has(B_REF_KEY)) ? attrs.get(B_REF_KEY).getAsDouble() : null;
		} catch (final Exception e) {
			return null;
		}
	}

	private static Double readDoubleFromJsonFile(final Path path, final String key) {
		try {
			if (!Files.isRegularFile(path)) {
				return null;
			}
			final JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			return obj.has(key) ? obj.get(key).getAsDouble() : null;
		} catch (final Exception e) {
			return null;
		}
	}

	private static int parseValue(final Pattern pattern, final String tileId, final String label) {
		final Matcher matcher = pattern.matcher(tileId);
		if (!matcher.find()) {
			throw new IllegalArgumentException("cannot derive " + label + " from tile id " + tileId);
		}
		return Integer.parseInt(matcher.group(1));
	}

	/** Reads a 1D coordinate array and returns a map from each stored label value to its array position. */
	private Map<Integer, Integer> readCoordinateIndex(final N5Reader reader, final String dataset) {
		final double[] values = readCoordinateValues(reader, dataset);
		if (values == null) {
			throw new IllegalArgumentException("coordinate array '" + dataset + "' not found in the zarr container; " +
					"point --zarrPath at a container that physically contains the scan/slab/sfov coordinate arrays");
		}
		final Map<Integer, Integer> labelToPosition = new HashMap<>();
		for (int i = 0; i < values.length; i++) {
			labelToPosition.put((int) Math.round(values[i]), i);
		}
		return labelToPosition;
	}

	/** Reads a 1D array into an int array indexed by position, or returns null if the dataset is absent. */
	private int[] readOptionalIntArray(final N5Reader reader, final String dataset) {
		final double[] values;
		try {
			values = readCoordinateValues(reader, dataset);
		} catch (final Exception e) {
			LOG.warn("readOptionalIntArray: could not read {} ({}), slab cross-check will be unavailable", dataset, e.getMessage());
			return null;
		}
		if (values == null) {
			LOG.warn("readOptionalIntArray: dataset {} not found, slab cross-check will be unavailable", dataset);
			return null;
		}
		final int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = (int) Math.round(values[i]);
		}
		return result;
	}

	/**
	 * Reads all values of a 1D coordinate array as doubles, or returns null if the array's metadata is not present.
	 * <p>
	 * n5-zarr 1.3.5 fails to parse a {@code .zarray} whose {@code fill_value} is JSON {@code null} (which is how
	 * xarray writes coordinate arrays), so {@link N5Utils#open} cannot be used here. Instead the {@code .zarray}
	 * JSON is read directly, its {@code fill_value} is patched to a parseable value (it is irrelevant for chunks
	 * that are physically present), the resulting {@link ZarrDatasetAttributes} is built via the reader, and the
	 * chunks are read with {@link N5Reader#readBlock} (which honors the zarr little-endian byte order).
	 */
	private double[] readCoordinateValues(final N5Reader reader, final String dataset) {
		final JsonObject zArray = readZArrayJson(dataset);
		if (zArray == null) {
			return null;
		}
		if (!zArray.has("fill_value") || zArray.get("fill_value").isJsonNull()) {
			zArray.add("fill_value", new JsonPrimitive("0"));
		}
		if (!(reader instanceof ZarrKeyValueReader)) {
			throw new IllegalArgumentException("expected a zarr reader but got " + reader.getClass().getName());
		}
		final ZarrDatasetAttributes attributes = ((ZarrKeyValueReader) reader).createDatasetAttributes(zArray);
		if (attributes == null) {
			throw new IllegalArgumentException("could not parse .zarray for coordinate dataset " + dataset);
		}
		if (attributes.getNumDimensions() != 1) {
			throw new IllegalArgumentException("coordinate array " + dataset + " is expected to be 1-dimensional but has "
					+ attributes.getNumDimensions() + " dimensions");
		}

		final int length = (int) attributes.getDimensions()[0];
		final int chunkSize = attributes.getBlockSize()[0];
		final DataType dataType = attributes.getDataType();
		final double[] values = new double[length];
		final int numChunks = (int) Math.ceil((double) length / chunkSize);
		for (int chunk = 0; chunk < numChunks; chunk++) {
			final DataBlock<?> block = reader.readBlock(dataset, attributes, (long) chunk);
			if (block == null) {
				throw new IllegalArgumentException("missing chunk " + chunk + " of coordinate array " + dataset);
			}
			copyBlockValues(block.getData(), dataType, values, chunk * chunkSize);
		}
		return values;
	}

	/** Reads the raw .zarray JSON for a dataset from the per-array file, falling back to consolidated .zmetadata. */
	private JsonObject readZArrayJson(final String dataset) {
		final Path perArray = Paths.get(params.zarrPath, dataset, ".zarray");
		try {
			if (Files.isRegularFile(perArray)) {
				return JsonParser.parseString(Files.readString(perArray)).getAsJsonObject();
			}
		} catch (final Exception e) {
			LOG.warn("readZArrayJson: failed to read {} ({})", perArray, e.getMessage());
		}
		try {
			final Path zMetadata = Paths.get(params.zarrPath, ".zmetadata");
			if (Files.isRegularFile(zMetadata)) {
				final JsonObject root = JsonParser.parseString(Files.readString(zMetadata)).getAsJsonObject();
				final JsonObject metadata = root.getAsJsonObject("metadata");
				if (metadata != null) {
					final JsonObject zArray = metadata.getAsJsonObject(dataset + "/.zarray");
					if (zArray != null) {
						return zArray.deepCopy();
					}
				}
			}
		} catch (final Exception e) {
			LOG.warn("readZArrayJson: failed to read .zmetadata for {} ({})", dataset, e.getMessage());
		}
		return null;
	}

	/** Copies a decoded data block into dst[offset...], applying unsigned promotion based on the data type. */
	private static void copyBlockValues(final Object data, final DataType dataType, final double[] dst, final int offset) {
		if (data instanceof byte[]) {
			final byte[] a = (byte[]) data;
			final boolean unsigned = dataType == DataType.UINT8;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = unsigned ? (a[i] & 0xFF) : a[i];
			}
		} else if (data instanceof short[]) {
			final short[] a = (short[]) data;
			final boolean unsigned = dataType == DataType.UINT16;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = unsigned ? (a[i] & 0xFFFF) : a[i];
			}
		} else if (data instanceof int[]) {
			final int[] a = (int[]) data;
			final boolean unsigned = dataType == DataType.UINT32;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = unsigned ? (a[i] & 0xFFFFFFFFL) : a[i];
			}
		} else if (data instanceof long[]) {
			final long[] a = (long[]) data;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = a[i];
			}
		} else if (data instanceof float[]) {
			final float[] a = (float[]) data;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = a[i];
			}
		} else if (data instanceof double[]) {
			final double[] a = (double[]) data;
			for (int i = 0; i < a.length && offset + i < dst.length; i++) {
				dst[offset + i] = a[i];
			}
		} else {
			throw new IllegalArgumentException("unsupported coordinate block data type " + data.getClass());
		}
	}

	/**
	 * Opens the (float32) homogenization array lazily.
	 * <p>
	 * Uses the lower-level {@link N5Utils#open} overload that relies on {@code getDatasetAttributes} and skips the
	 * {@code isLabelMultisetType} check. The label-multiset check calls {@code getAttribute}, which in the shaded
	 * client jar throws an NPE while serializing the blosc compressor (the n5-blosc {@code Compression} service
	 * registration is stripped during shading); this overload avoids that path.
	 */
	private static RandomAccessibleInterval<FloatType> openHomogenizationArray(final N5Reader reader, final String dataset) {
		final Consumer<IterableInterval<FloatType>> noMissingBlockHandler = blocks -> { };
		return N5Utils.open(reader, dataset, noMissingBlockHandler, AccessFlags.setOf());
	}

	private static int findAxisForSize(final RandomAccessibleInterval<?> rai, final long size, final String label) {
		int found = -1;
		for (int d = 0; d < rai.numDimensions(); d++) {
			if (rai.dimension(d) == size) {
				if (found != -1) {
					throw new IllegalArgumentException("cannot unambiguously identify the " + label +
							" axis: dimensions " + found + " and " + d + " both have size " + size);
				}
				found = d;
			}
		}
		if (found == -1) {
			throw new IllegalArgumentException("no axis of the correction array has the expected " + label +
					" size " + size);
		}
		return found;
	}

	private static int remainingAxis(final RandomAccessibleInterval<?> rai, final int... usedAxes) {
		for (int d = 0; d < rai.numDimensions(); d++) {
			boolean used = false;
			for (final int usedAxis : usedAxes) {
				if (usedAxis == d) {
					used = true;
					break;
				}
			}
			if (!used) {
				return d;
			}
		}
		throw new IllegalArgumentException("could not identify the parameter axis of the correction array");
	}

	private static final Logger LOG = LoggerFactory.getLogger(ThomasCalibrationIntensityCorrectionClient.class);
}
