package org.janelia.render.client;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.janelia.alignment.ByteBoxRenderer;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;

import java.io.IOException;
import java.util.List;

public class TileRenderBenchmark {
	public static void main(final String[] args) throws IOException {
		final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
		final String owner = "fibsem";
		final String project = "jrc_P3_E2_D1_Lip4_19";
		final String stack = "v1_acquire_trimmed_align";
		final double z = 2000.0;
		final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, owner, project);
		final ResolvedTileSpecCollection rtsc = renderDataClient.getResolvedTiles(stack, z);
		final Bounds bounds = rtsc.toBounds();

		// pure loading, no processing
		final TileSpec tile = rtsc.getTileSpecs().stream().findFirst().orElseThrow();
		final List<ChannelSpec> channels = tile.getAllChannels();
		final ImageAndMask imageAndMask = channels.get(0).getFirstMipmapImageAndMask(tile.getTileId());
		final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;
		final ImageProcessor ip = cache.get(imageAndMask.getImageUrl(), 0, false, false, imageAndMask.getImageLoaderType(), 0);

		final ByteBoxRenderer boxRenderer = new ByteBoxRenderer(baseDataUrl,
																owner,
																project,
																stack,
																bounds.getWidth(),
																bounds.getHeight(),
																1.0,
																0.0,
																255.0,
																false);
		final ImageProcessor renderedIp = boxRenderer.render(bounds.getMinX().longValue(), bounds.getMinY().longValue(), bounds.getMinZ().longValue(), cache);

		new ImageJ();
		new ImagePlus("Test", ip).show();
		new ImagePlus("Rendered", renderedIp).show();
	}
}
