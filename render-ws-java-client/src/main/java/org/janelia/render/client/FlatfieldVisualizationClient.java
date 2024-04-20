package org.janelia.render.client;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * This is a one-off client for visualizing the estimated flat field of the Hess Lab's wafer_53
 */
public class FlatfieldVisualizationClient {
	// the path to the data (converted to 8-bit PNGs; must be on a shared filesystem accessible to the web server)
	private static final String DATA_PATH = "/nrs/hess/render/flatfield/flatfields_n2000";
	// the format for the files (fill in z and sfov number for the placeholders, in order)
	private static final String NAME_FORMAT = "flat_field_z%03d_sfov%03d_n2000.png";
	// the number of z slices
	private static final int Z_SLICES = 47;

	// stack location
	private static final String BASE_URL = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
	private static final String OWNER = "hess_wafer_53d";
	private static final String PROJECT = "slab_000_to_009";
	private static final String STACK = "flatfield_test1";
	// reuse metadata from random stack in same project to set up new stack
	private static final String TEMPLATE_STACK = "s000_m209";

	public static void main(final String[] args) throws IOException {
		LOG.info("main: setting up target stack {}", STACK);
		final RenderDataClient dataClient = new RenderDataClient(BASE_URL, OWNER, PROJECT);
		final StackMetaData projectSpecificMetaData = dataClient.getStackMetaData(TEMPLATE_STACK);
		dataClient.setupDerivedStack(projectSpecificMetaData, STACK);

		final Map<Integer, TileSpec> tileSpecTemplate = getTemplateLayer(dataClient);
		for (int z = 1; z <= Z_SLICES; z++) {
			final ResolvedTileSpecCollection slice = assembleSlice(tileSpecTemplate, z);
			dataClient.saveResolvedTiles(slice, STACK, (double) z);
		}

		LOG.info("main: completing target stack {}", STACK);
		dataClient.setStackState(STACK, StackMetaData.StackState.COMPLETE);
	}

	private static Map<Integer, TileSpec> getTemplateLayer(final RenderDataClient dataClient) throws IOException {
		LOG.info("getTemplateLayer: getting template layer from stack {}", TEMPLATE_STACK);
		final ResolvedTileSpecCollection realTileSpecs = dataClient.getResolvedTiles(TEMPLATE_STACK, 1.0, ".*_000001_.*");

		final Map<Integer, TileSpec> templateTileSpecs = new HashMap<>();
		final int rowOffset = 10;

		final TileSpec firstTileSpec = realTileSpecs.getTileSpecs().stream().findFirst().orElseThrow();
		final double height = firstTileSpec.getHeight();
		final double width = firstTileSpec.getWidth();

		for (final TileSpec tileSpec : realTileSpecs.getTileSpecs()) {
			final TileSpec templateTileSpec = new TileSpec();

			// set layout
			final LayoutData originalLayout = tileSpec.getLayout();
			final LayoutData layoutData = new LayoutData(
					"1.0",
					originalLayout.getTemca(),
					originalLayout.getCamera(),
					originalLayout.getImageRow() - rowOffset,
					originalLayout.getImageCol(),
					originalLayout.getImageCol() * width,
					(originalLayout.getImageRow() - rowOffset) * height,
					0.0
			);
			templateTileSpec.setLayout(layoutData);

			// set bounds
			final Rectangle bounds = new Rectangle(templateTileSpec.getLayout().getStageX().intValue(), templateTileSpec.getLayout().getStageY().intValue(), (int) width, (int) height);
			templateTileSpec.setBoundingBox(bounds, 2000.0);
			templateTileSpec.setWidth(width);
			templateTileSpec.setHeight(height);

			// insert at correct position
			final int sfovNumber = getSfovNumber(tileSpec.getTileId());
			templateTileSpecs.put(sfovNumber, templateTileSpec);
		}

		return templateTileSpecs;
	}

	private static int getSfovNumber(final String tileId) {
		final String[] parts = tileId.split("_");
		return Integer.parseInt(parts[2]);
	}

	private static ResolvedTileSpecCollection assembleSlice(final Map<Integer, TileSpec> templateTileSpecs, final int z) {
		LOG.info("assembleSlice: assembling slice {}", z);
		final ResolvedTileSpecCollection slice = new ResolvedTileSpecCollection();
		for (final Map.Entry<Integer, TileSpec> entry : templateTileSpecs.entrySet()) {
			final int sfov = entry.getKey();
			final TileSpec template = entry.getValue();

			// set metadata
			final TileSpec tileSpec = new TileSpec();
			tileSpec.setTileId(String.format("z%03d_sfov%03d", z, sfov));
			tileSpec.setLayout(template.getLayout());
			tileSpec.setBoundingBox(template.toTileBounds().toRectangle(), 2000.0);
			tileSpec.setWidth((double) template.getWidth());
			tileSpec.setHeight((double) template.getHeight());
			tileSpec.setZ((double) z);

			// set image
			final String imagePath = DATA_PATH + "/" + String.format(NAME_FORMAT, z, sfov);
			final ChannelSpec channelSpec = createChannelSpec(imagePath);
			tileSpec.addChannel(channelSpec);
			tileSpec.getFirstMipmapEntry();
			slice.addTileSpecToCollection(tileSpec);
		}
		return slice;
	}

	private static ChannelSpec createChannelSpec(String imagePath) {
		final MipmapPathBuilder mipmapPathBuilder = new MipmapPathBuilder(imagePath, 0, ".png", null);
		final TreeMap<Integer, ImageAndMask> mipmapLevels = new TreeMap<>();
		mipmapLevels.put(0, new ImageAndMask(imagePath, null));
		return new ChannelSpec("default", null, null, mipmapLevels, mipmapPathBuilder, null);
	}

	private static final Logger LOG = LoggerFactory.getLogger(FlatfieldVisualizationClient.class);
}
