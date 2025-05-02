package org.janelia.render.client.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import edu.mines.jtk.dsp.FftComplex;
import edu.mines.jtk.dsp.FftReal;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.destreak.SmoothMaskStreakCorrector;
import org.janelia.alignment.destreak.StreakCorrector;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import spim.Threads;

import javax.swing.SwingUtilities;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.IOException;


public class StreakCorrection_Plugin implements PlugIn {

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

	private static StreakCorrectionParameters defaultParameters = new StreakCorrectionParameters();
	private static int nSteps = 3;
	private static double stepSize = 1.0;
	private static int defaultChoice = 0;
	private static String[] parameterChoices = new String[] { "None", "innerCutoff", "bandWidth", "angle",
			"gaussianBlurRadius", "initialThreshold", "finalThreshold" };


	@Override
	public void run(final String arg) {
		final GenericDialog dialog = new GenericDialog("Fit shading correction");

		dialog.addMessage("Streak correction parameters");
		dialog.addNumericField("Inner cutoff", defaultParameters.innerCutoff);
		dialog.addNumericField("Band width", defaultParameters.bandWidth);
		dialog.addNumericField("Angle", defaultParameters.angle);

		dialog.addMessage("Localization parameters");
		dialog.addCheckbox("Localize correction", defaultParameters.localize);
		dialog.addNumericField("Gaussian blur radius", defaultParameters.gaussianBlurRadius);
		dialog.addNumericField("Initial threshold", defaultParameters.initialThreshold);
		dialog.addNumericField("Final threshold", defaultParameters.finalThreshold);

		dialog.addMessage("Parameter variation");
		dialog.addChoice("Parameter to vary", parameterChoices, parameterChoices[defaultChoice]);
		dialog.addNumericField("Step size", stepSize);
		dialog.addNumericField("Number of steps", nSteps);

		dialog.showDialog();

		if (dialog.wasCanceled()) {
			return;
		}

		defaultParameters.setFromDialog(dialog);

		final ImagePlus img = IJ.getImage();
		final int width = img.getWidth();
		final int height = img.getHeight();
		final StreakCorrector corrector = defaultParameters.getCorrector(width, height);

		try {
			final ImagePlus corrected = new ImagePlus("Corrected", img.getProcessor().duplicate());
			corrector.process(corrected.getProcessor(), 1.0);
			corrected.show();
		} catch (final Exception e) {
			IJ.log("Streak correction failed: " + e.getMessage());
		}

	}

	private static void addKeyListener() {
		System.out.println("Mapped 'Streak Correction' to F1.");

		new Thread(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.addKeyEventDispatcher(e -> {
					if (e.getID() == KeyEvent.KEY_PRESSED) {
						if (e.getKeyCode() == KeyEvent.VK_F1) {
							new StreakCorrection_Plugin().run(null);
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

		return new ImagePlus(tileSpec.getTileId(), ip);
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


	private static class StreakCorrectionParameters {
		public int innerCutoff = 15;
		public int bandWidth = 10;
		public double angle = 0.0;
		public int gaussianBlurRadius = 10;
		public double initialThreshold = 7.0;
		public double finalThreshold = 0.05;
		public boolean localize = true;

		public StreakCorrectionParameters() {
			// Default constructor
		}

		public StreakCorrectionParameters(final StreakCorrectionParameters other) {
			this.innerCutoff = other.innerCutoff;
			this.bandWidth = other.bandWidth;
			this.angle = other.angle;
			this.gaussianBlurRadius = other.gaussianBlurRadius;
			this.initialThreshold = other.initialThreshold;
			this.finalThreshold = other.finalThreshold;
			this.localize = other.localize;
		}

		public StreakCorrector getCorrector(final int width, final int height) {
			// The following computations are based on the original code in net.imglib2.algorithm.fft.FourierTransform
			final int extendedWidth = width + Math.max(Math.round(1.25f * width) - width, 12);
			final int extendedHeight = height + Math.max(Math.round(1.25f * height) - height, 12);
			final int fftWidth = FftReal.nfftFast(extendedWidth) / 2 + 1;
			final int fftHeight = FftComplex.nfftFast(extendedHeight);

			final SmoothMaskStreakCorrector corrector = new SmoothMaskStreakCorrector(
					Threads.numThreads() / 2, fftWidth, fftHeight, innerCutoff, bandWidth, angle);

			return corrector;
		}

		public void setFromDialog(final GenericDialog dialog) {
			innerCutoff = (int) dialog.getNextNumber();
			bandWidth = (int) dialog.getNextNumber();
			angle = dialog.getNextNumber();
			localize = dialog.getNextBoolean();
			gaussianBlurRadius = (int) dialog.getNextNumber();
			initialThreshold = dialog.getNextNumber();
			finalThreshold = dialog.getNextNumber();
		}
	}
}
