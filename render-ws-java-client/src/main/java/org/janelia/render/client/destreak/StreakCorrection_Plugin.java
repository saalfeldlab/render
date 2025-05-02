package org.janelia.render.client.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.emshading.ShadingCorrection_Plugin;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.IOException;


public class StreakCorrection_Plugin implements PlugIn {

	private static final Logger LOG = LoggerFactory.getLogger(StreakCorrection_Plugin.class);

	private static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		public RenderWebServiceParameters renderWebService = new RenderWebServiceParameters();

		@Parameter(names = "--stack",
				description = "Name of the stack in the render project",
				required = true)
		public String stack;

		@Parameter(names = "--z",
				description = "Z slice to process")
		public int z = 1;

		@Parameter(names = "--tileNumber",
				description = "Number of tile to process (alphabetical order)")
		public int tileNumber = 0;
	}

	@Override
	public void run(final String arg) {

	}

	private static void addKeyListener() {
		System.out.println("Mapped 'Streak Correction' to F1.");

		new Thread(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.addKeyEventDispatcher(e -> {
					if (e.getID() == KeyEvent.KEY_PRESSED) {
						if (e.getKeyCode() == KeyEvent.VK_F1) {
							new ShadingCorrection_Plugin().run(null);
						}
					}
					return false;
				})
		).start();
	}

	private static ImagePlus loadImage(final RenderDataClient client, final Parameters params) throws IOException {
		final ResolvedTileSpecCollection rtsc = client.getResolvedTiles(params.stack, (double) params.z);
		if (rtsc == null) {
			throw new IOException("Failed to load tile specs for " + params.stack + " z=" + params.z);
		}
		final TileSpec tileSpec = rtsc.getTileSpecs().stream().sorted().findFirst().orElseThrow();
		IJ.log("Show tile: " + tileSpec.getTileId() + " from z=" + params.z);

		final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;
		final ChannelSpec firstChannel = tileSpec.getAllChannels().stream().findFirst().orElseThrow();
		final ImageAndMask imageAndMask = firstChannel.getMipmap(0);
		final ImageProcessor ip = cache.get(imageAndMask.getImageUrl(), 0, false, false, imageAndMask.getImageLoaderType(), null);
		final FloatProcessor fip = ip.convertToFloatProcessor();

		return new ImagePlus(tileSpec.getTileId(), fip);
	}

	public static void main(final String[] args) throws IOException {
		final StreakCorrection_Plugin.Parameters params = new StreakCorrection_Plugin.Parameters();
		params.parse(args);

		new ImageJ();
		SwingUtilities.invokeLater(StreakCorrection_Plugin::addKeyListener);

		IJ.log("Opening " + params.renderWebService.owner + "/" + params.renderWebService.project + "/" + params.stack);

		final RenderDataClient client = params.renderWebService.getDataClient();
		final ImagePlus img = loadImage(client, params);

		img.show();
	}
}
