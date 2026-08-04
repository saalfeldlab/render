package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.BeamCorrectionParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.ZarrDatasetAttributes;
import org.janelia.saalfeldlab.n5.zarr.ZarrKeyValueReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Java client that applies the pre-computed degree-0 (spatially flat) beam-homogenization correction
 * for a Multi-SEM stack.
 * <p>
 * The correction parameters are read from a multi-SEM acquisition zarr container (the "xlog",
 * e.g. xlog_wafer_61.zarr) that is opened with {@link N5Factory} and can therefore be either a local
 * file system path or a cloud URI (e.g. gs://janelia-spark-test/xlog_data/wafer_61.zarr).
 * The {@code beam_homogenization} array has dimensions
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

		@Parameter(names = "--stack", description = "Source stack to correct", required = true)
		public String stack;

		@ParametersDelegate
		public BeamCorrectionParameters beam = new BeamCorrectionParameters();
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {
				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				parameters.beam.validate();

				final ThomasCalibrationIntensityCorrectionClient client = new ThomasCalibrationIntensityCorrectionClient();
				final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
				client.correctStack(dataClient,
									parameters.stack,
									parameters.beam,
									true);
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
	private static final String ZMETADATA_FILE = ".zmetadata";

	public ThomasCalibrationIntensityCorrectionClient() {
	}

	public void correctStack(final RenderDataClient dataClient,
							 final String stack,
							 final BeamCorrectionParameters beam,
							 final boolean completeStack) throws IOException {


		final String targetStack = stack + beam.targetStackSuffix;

		try (final N5Reader reader = new N5Factory().openReader(StorageFormat.ZARR, beam.zarrPath)) {
			LOG.info("correctStack: opened {} using {}", beam.zarrPath, reader.getClass().getSimpleName());

			final ZarrKeyValueReader zarrReader = asZarrReader(reader, beam.zarrPath);

			final double referenceLevel = resolveReferenceLevel(zarrReader, beam);

			// coordinate-value (label) -> array-position lookups
			final Map<Integer, Integer> scanLabelToPosition = readCoordinateIndex(zarrReader, beam.scanDataset);
			final Map<Integer, Integer> slabLabelToPosition = readCoordinateIndex(zarrReader, beam.slabDataset);
			final Map<Integer, Integer> sfovLabelToPosition = readCoordinateIndex(zarrReader, beam.sfovDataset);
			final int[] serialBySlabPosition = readOptionalIntArray(zarrReader, beam.serialDataset);

			LOG.info("correctStack: reference level (b_ref) is {}, source data inverted is {}; coordinate arrays loaded - {} scans, {} slabs, {} sfovs",
					 referenceLevel, beam.inverted, scanLabelToPosition.size(), slabLabelToPosition.size(), sfovLabelToPosition.size());

			// the 4D correction array; axes are matched to coordinate sizes so the code is robust to axis order
			final RandomAccessibleInterval<? extends RealType<?>> homogenization = openHomogenizationArray(reader, beam.homogenizationDataset);
			final int scanAxis = findAxisForSize(homogenization, scanLabelToPosition.size(), "scan");
			final int slabAxis = findAxisForSize(homogenization, slabLabelToPosition.size(), "slab");
			final int sfovAxis = findAxisForSize(homogenization, sfovLabelToPosition.size(), "sfov");
			final int parameterAxis = remainingAxis(homogenization, scanAxis, slabAxis, sfovAxis);
			final long parameterCount = homogenization.dimension(parameterAxis);

			if (beam.gainIndex < 0 || beam.gainIndex >= parameterCount
					|| beam.deg0Index < 0 || beam.deg0Index >= parameterCount) {
				throw new IllegalArgumentException("gainIndex " + beam.gainIndex + " and deg0Index " + beam.deg0Index +
						" must both be within the parameter dimension of size " + parameterCount);
			}

			LOG.info("correctStack: '{}' axis mapping is scan={}, slab={}, sfov={}, parameter={} (size {}); using gainIndex={}, deg0Index={}",
					 beam.homogenizationDataset, scanAxis, slabAxis, sfovAxis, parameterAxis, parameterCount,
					 beam.gainIndex, beam.deg0Index);

			final RandomAccess<? extends RealType<?>> access = homogenization.randomAccess();
			final long[] position = new long[homogenization.numDimensions()];

			final List<Double> zValues = dataClient.getStackZValues(stack);
			if (zValues.isEmpty()) {
				throw new IllegalArgumentException("source stack " + stack + " does not contain any matching z values");
			}

			final StackMetaData sourceStackMetaData = dataClient.getStackMetaData(stack);
			dataClient.setupDerivedStack(sourceStackMetaData, targetStack);

			int correctedCount = 0;
			int skippedCount = 0;

			for (final Double z : zValues) {
				final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(stack, z);

				for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
					final String tileId = tileSpec.getTileId();

					final int magc = parseValue(MAGC_PATTERN, tileId, "magc");
					final int scan = parseValue(SCAN_PATTERN, tileId, "scan");
					final int sfov = parseValue(SFOV_PATTERN, tileId, "sfov");
					final int sfovLabel = sfov + beam.sfovLabelOffset;

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

					position[parameterAxis] = beam.gainIndex;
					access.setPosition(position);
					final double gain = access.get().getRealDouble();

					position[parameterAxis] = beam.deg0Index;
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

					tileSpec.setFilterSpec(buildHomogenizationFilterSpec(gain, degree0, referenceLevel, beam.inverted));
					tileSpec.convertSingleChannelSpecToLegacyForm();
					correctedCount++;
				}

				dataClient.saveResolvedTiles(resolvedTiles, targetStack, z);
				LOG.info("correctStack: saved z {} to {}", z, targetStack);
			}

			if (completeStack) {
				dataClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);
			}

			LOG.info("correctStack: exit, applied degree-0 homogenization to {} tiles and skipped {} tiles across {} layers of {} (target stack {})",
					 correctedCount, skippedCount, zValues.size(), stack, targetStack);
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

	private double resolveReferenceLevel(final ZarrKeyValueReader reader,
										 final BeamCorrectionParameters beam) {
		if (beam.referenceLevel != null) {
			return beam.referenceLevel;
		}
		// NOTE: n5-zarr's reader.getAttribute(...) throws an NPE on blosc-compressed arrays because it
		// serializes the compressor, so read the b_ref value directly from the JSON metadata.
		Double bRef = readDouble(readJsonResource(reader, beam.homogenizationDataset, ZarrKeyValueReader.ZATTRS_FILE),
								 B_REF_KEY);
		if (bRef == null) {
			bRef = readDouble(readConsolidatedJson(reader, beam.homogenizationDataset, ZarrKeyValueReader.ZATTRS_FILE),
							  B_REF_KEY);
		}
		if (bRef == null) {
			throw new IllegalArgumentException("could not read '" + B_REF_KEY + "' from the " +
			                                   beam.homogenizationDataset + " metadata under " + beam.zarrPath +
					"; specify --referenceLevel explicitly");
		}
		LOG.info("resolveReferenceLevel: using reference level {}={} from zarr metadata", B_REF_KEY, bRef);
		return bRef;
	}

	/**
	 * Reads a JSON metadata resource (e.g. {@code .zarray} or {@code .zattrs}) for a dataset,
	 * falling back to the consolidated {@code .zmetadata} at the container root.
	 * <p>
	 * The resource is read with the container's {@code KeyValueAccess} instead of {@link java.nio.file.Files}
	 * so that metadata can be read from a local file system path or from a cloud URI
	 * (e.g. {@code gs://bucket/xlog_wafer_61.zarr}).
	 *
	 * @return the parsed resource or null if it is not present in the container.
	 */
	private static JsonObject readDatasetJson(final ZarrKeyValueReader reader,
											  final String dataset,
											  final String resourceName) {

		final JsonObject json = readJsonResource(reader, dataset, resourceName);
		return json == null ? readConsolidatedJson(reader, dataset, resourceName) : json;
	}

	/** Reads a dataset's JSON metadata resource from the consolidated {@code .zmetadata} at the container root. */
	private static JsonObject readConsolidatedJson(final ZarrKeyValueReader reader,
												   final String dataset,
												   final String resourceName) {
		final JsonObject consolidated = readJsonResource(reader, "", ZMETADATA_FILE);
		final JsonObject metadata = consolidated == null ? null : consolidated.getAsJsonObject("metadata");
		final JsonObject datasetJson = metadata == null ? null : metadata.getAsJsonObject(dataset + "/" + resourceName);
		return datasetJson == null ? null : datasetJson.deepCopy();
	}

	private static Double readDouble(final JsonObject json,
									 final String key) {
		return ((json != null) && json.has(key)) ? json.get(key).getAsDouble() : null;
	}

	/** Reads one JSON resource from the container, or returns null if it is missing or cannot be parsed. */
	private static JsonObject readJsonResource(final ZarrKeyValueReader reader,
											   final String parentPath,
											   final String resourceName) {
		try {
			final JsonElement element = reader.getAttributesFromContainer(N5URI.normalizeGroupPath(parentPath),
																		  resourceName);
			return ((element != null) && element.isJsonObject()) ? element.getAsJsonObject() : null;
		} catch (final Exception e) {
			LOG.warn("readJsonResource: failed to read {} for '{}' ({})", resourceName, parentPath, e.getMessage());
			return null;
		}
	}

	private static ZarrKeyValueReader asZarrReader(final N5Reader reader,
												   final String zarrPath) {
		if (!(reader instanceof ZarrKeyValueReader)) {
			throw new IllegalArgumentException("expected a zarr reader for " + zarrPath +
											   " but got " + reader.getClass().getName());
		}
		return (ZarrKeyValueReader) reader;
	}

	private static int parseValue(final Pattern pattern, final String tileId, final String label) {
		final Matcher matcher = pattern.matcher(tileId);
		if (!matcher.find()) {
			throw new IllegalArgumentException("cannot derive " + label + " from tile id " + tileId);
		}
		return Integer.parseInt(matcher.group(1));
	}

	/** Reads a 1D coordinate array and returns a map from each stored label value to its array position. */
	private static Map<Integer, Integer> readCoordinateIndex(final ZarrKeyValueReader reader,
															 final String dataset) {
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
	private static int[] readOptionalIntArray(final ZarrKeyValueReader reader,
											  final String dataset) {
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
	private static double[] readCoordinateValues(final ZarrKeyValueReader reader,
												 final String dataset) {
		final JsonObject zArray = readDatasetJson(reader, dataset, ZarrKeyValueReader.ZARRAY_FILE);
		if (zArray == null) {
			return null;
		}
		if (!zArray.has("fill_value") || zArray.get("fill_value").isJsonNull()) {
			zArray.add("fill_value", new JsonPrimitive("0"));
		}
		final ZarrDatasetAttributes attributes = reader.createDatasetAttributes(zArray);
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
			final DataBlock<?> block = reader.readBlock(dataset, attributes, chunk);
			if (block == null) {
				throw new IllegalArgumentException("missing chunk " + chunk + " of coordinate array " + dataset);
			}
			copyBlockValues(block.getData(), dataType, values, chunk * chunkSize);
		}
		return values;
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
