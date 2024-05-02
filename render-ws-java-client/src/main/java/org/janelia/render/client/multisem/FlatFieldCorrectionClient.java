package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Apply a previously computed flat field estimate to correct shading in a Multi-SEM project.
 * Originally developed for the wafer_53 Multi-SEM dataset.
 *
 * @author Michael Innerberger
 */
public class FlatFieldCorrectionClient {

	// make cache large enough to hold all flat field estimates for one layer
	private final Parameters params;
	private final ImageProcessorCache cache;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final MultiProjectParameters multiProject = new MultiProjectParameters();
		@Parameter(names = "--flatFieldLocation", description = "Location of the flat field estimates", required = true)
		private String flatFieldLocation;
		@Parameter(names = "--outputRoot", description = "Folder to write corrected images to", required = true)
		private String outputFolder;
		@Parameter(names = "--flatFieldFormat", description = "Format of the flat field estimates in printf style with placeholders for z-layer and sfov", required = true)
		private String flatFieldFormat;
		@Parameter(names = "--targetStackSuffix", description = "Suffix to append to the stack name for the corrected stack", required = true)
		private String targetStackSuffix = "_corrected";
		@Parameter(names = "--inputRoot", description = "Root folder for input data; if given, the structure under this root is replicated in the output folder")
		private String inputRoot = null;
		@Parameter(names = "--flatFieldConstantFromZ", description = "Maximum z-layer of flat field estimates to consider. All subsequent z-layers get corrected with the maximum, since the estimates can be bad for the last few z-layers. If not given, all z-layers are considered")
		private Integer flatFieldConstantFromZ = Integer.MAX_VALUE;
		@Parameter(names = "--cacheSizeGb", description = "Size of the image processor cache in GB (should be enough to hold one layer of flat field estimates)")
		private double cacheSizeGb = 1.5;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws IOException {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final FlatFieldCorrectionClient client = new FlatFieldCorrectionClient(parameters);
				client.correctTiles();
			}
		};
		clientRunner.run();
	}

	public FlatFieldCorrectionClient(final Parameters parameters) {
		this.params = parameters;
		final double cacheSizeInBytes = 1_000_000_000 * parameters.cacheSizeGb;
		this.cache = new ImageProcessorCache((long) cacheSizeInBytes, false, false);
	}

	public void correctTiles() throws IOException {
		final RenderDataClient renderClient = new RenderDataClient(params.multiProject.baseDataUrl, params.multiProject.owner, params.multiProject.project);
		final List<StackId> stacks = params.multiProject.stackIdWithZ.getStackIdList(renderClient);

		// TODO: iterate by z-layer first to re-use the flat field estimate for all stacks?
		for (final StackId stack : stacks) {
			correctTilesForStack(params.multiProject.baseDataUrl, stack);
		}
	}

	public void correctTilesForStack(final String baseDataUrl, final StackId stack) throws IOException {
			final RenderDataClient stackClient = new RenderDataClient(baseDataUrl, stack.getOwner(), stack.getProject());
			final List<Double> zValues = stackClient.getStackZValues(stack.getStack());
			LOG.info("Correcting tiles for {} with {} z-layers", stack, zValues.size());

			final StackMetaData stackMetaData = stackClient.getStackMetaData(stack.getStack());
			stackClient.setupDerivedStack(stackMetaData, stack.getStack() + params.targetStackSuffix);

			for (final double z : zValues) {
				final ResolvedTileSpecCollection tileSpecs = stackClient.getResolvedTiles(stack.getStack(), z);

				for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
					final ImageProcessor ip = loadImageTile(tileSpec);
					final int sfov = extractSfovNumber(tileSpec);
					final ImageProcessor flatFieldEstimate = loadFlatFieldEstimate(Math.min(z, (double) params.flatFieldConstantFromZ), sfov);

					applyFlatFieldCorrection(ip, flatFieldEstimate);

					final Path newPath = getNewPath(tileSpec);
					ensureFolderExists(newPath.getParent());
					patchTileSpec(tileSpec, newPath);
					saveImage(ip, tileSpec);
				}

				stackClient.saveResolvedTiles(tileSpecs, stack.getStack() + params.targetStackSuffix, z);
			}
			stackClient.setStackState(stack.getStack() + params.targetStackSuffix, StackMetaData.StackState.COMPLETE);
	}

	private ImageProcessor loadFlatFieldEstimate(final double z, final int sfov) {
		final Path imagePath = Path.of(params.flatFieldLocation, String.format(params.flatFieldFormat, (int) z, sfov));
		final String imageUrl = "file:" + imagePath;
		final ImageLoader.LoaderType loaderType = ImageLoader.LoaderType.IMAGEJ_DEFAULT;
		return cache.get(imageUrl, 0, false, false, loaderType, null);
	}

	private int extractSfovNumber(final TileSpec tileSpec) {
		final String tileId = tileSpec.getTileId();
		final String[] parts = tileId.split("_");
		return Integer.parseInt(parts[1].substring(4));
	}

	/**
	 * Apply the flat field estimate to the input image.
	 * @param ip the input image that is altered in place
	 * @param flatFieldEstimate the flat field estimate to apply
	 */
	private void applyFlatFieldCorrection(final ImageProcessor ip, final ImageProcessor flatFieldEstimate) {
		// convert to 32-bit grayscale (float) for lossless processing
		final FloatProcessor fp = ip.convertToFloatProcessor();

		for (int i = 0; i < ip.getPixelCount(); i++) {
			final double a = fp.getf(i);
			final double b = flatFieldEstimate.getf(i);
			fp.setf(i, (float) (a / b));
		}

		// convert back to original bit depth
		fp.setMinAndMax(0, 255);
		ip.setPixels(0, fp);
	}

	private void patchTileSpec(final TileSpec tileSpec, final Path newPath) {
		final ChannelSpec firstChannel = tileSpec.getAllChannels().get(0);
		final ImageAndMask originalImage = firstChannel.getFirstMipmapEntry().getValue();
		final ImageAndMask newImage = originalImage.copyWithImage(newPath.toString(), null, null);
		firstChannel.putMipmap(0, newImage);
	}

	private Path getNewPath(final TileSpec tileSpec) {
		final Path originalPath = Path.of(tileSpec.getTileImageUrl().replaceFirst("file:", ""));
		if (params.inputRoot != null) {
			final Path relativePath = Path.of(params.inputRoot).relativize(originalPath);
			return Path.of(params.outputFolder).resolve(relativePath);
		} else {
			return Path.of(params.outputFolder).resolve(originalPath.getFileName());
		}
	}

	private void ensureFolderExists(final Path folder) {
		final boolean folderExists = folder.toFile().exists() || folder.toFile().mkdirs();

		if (!folderExists) {
			LOG.error("Could not create output folder: {}", folder);
			System.exit(1);
		}
	}

	private ImageProcessor loadImageTile(final TileSpec tileSpec) {
		final ChannelSpec firstChannelSpec = tileSpec.getAllChannels().get(0);
		final String tileId = tileSpec.getTileId();
		final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapImageAndMask(tileId);

		return ImageProcessorCache.DISABLED_CACHE.get(imageAndMask.getImageUrl(),
													  0,
													  false,
													  firstChannelSpec.is16Bit(),
													  imageAndMask.getImageLoaderType(),
													  imageAndMask.getImageSliceNumber());
	}

	private void saveImage(final ImageProcessor ip, final TileSpec tileSpec) {
		final String tileId = tileSpec.getTileId();
		final ImagePlus imp = new ImagePlus(tileId, ip);
		IJ.save(imp, tileSpec.getImagePath());
	}

	private static final Logger LOG = LoggerFactory.getLogger(FlatFieldCorrectionClient.class);
}
