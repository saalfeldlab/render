package org.janelia.render.client.emshading;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.swing.SwingUtilities;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.ImagePlus;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.alignment.filter.emshading.QuadraticShading;
import org.janelia.alignment.filter.emshading.ShadingModel;
import org.janelia.alignment.filter.emshading.FourthOrderShading;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.visualize.RenderTools;

import ij.IJ;
import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;

public class ShadingCorrection_Plugin implements PlugIn {

	public static final int MAX_SIZE = 2000 * 2000;

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

		@Parameter(names = "--scale",
				description = "Scale factor for downscaling")
		public double scale = 0.0;
	}


	public static int defaultType = 1;
	public static boolean defaultShowBackground = false;
	public static String[] fitTypes = new String[] { "Quadratic", "Fourth Order" };

	@Override
	public void run(final String arg) {
		final List<Roi> rois = getROIs();

		if (rois == null || rois.isEmpty()) {
			IJ.log("No ROIs specified.");
			return;
		}

		final GenericDialog gd = new GenericDialog("Fit shading correction");

		gd.addChoice("Fit type", fitTypes, fitTypes[defaultType]);
		gd.addCheckbox("Show shading image", defaultShowBackground);
		gd.showDialog();

		if (gd.wasCanceled()) {
			return;
		}

		final int type = gd.getNextChoiceIndex();
		defaultType = type;

		final boolean showBackground = gd.getNextBoolean();
		defaultShowBackground = showBackground;

		try {
			fit(type, rois, showBackground);
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			IJ.log("Fitting failed: " + e.getMessage());
		}
	}

	public static List<Roi> getROIs() {
		final RoiManager rm = RoiManager.getInstance();

		if (rm == null || rm.getCount() == 0) {
			IJ.log("Please define ROIs first before running shading correction.");
			return null;
		}

		return Arrays.asList(rm.getRoisAsArray());
	}

	public static void fit(final int type, final List<Roi> rois, final boolean showBackground) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		IJ.log("Fitting with " + fitTypes[type] + " model...");

		final ShadingModel shadingModel;
		final String modelType;
		if (fitTypes[type].equals("Quadratic")) {
			shadingModel = new QuadraticShading();
			modelType = "quadratic";
		} else if (fitTypes[type].equals("Fourth Order")) {
			shadingModel = new FourthOrderShading();
			modelType = "fourthOrder";
		} else {
			throw new IllegalArgumentException("Unknown fit type: " + fitTypes[type]);
		}

		final long start = System.currentTimeMillis();
		final RandomAccessibleInterval<UnsignedShortType> img = ImageJFunctions.wrap(IJ.getImage());
		CorrectShading.fitBackgroundModel(rois, img, shadingModel);
		IJ.log("Fitted shading model: " + shadingModel);
		IJ.log("Fitting took " + (System.currentTimeMillis() - start) + "ms.");
		IJ.log("\"modelType\": \"" + modelType + "\",");
		IJ.log("\"coefficients\": " + Arrays.toString(shadingModel.getCoefficients()));

		final RandomAccessibleInterval<FloatType> shading = CorrectShading.createBackgroundImage(shadingModel, img);
		final RandomAccessibleInterval<UnsignedShortType> corrected = CorrectShading.correctBackground(img, shading, new UnsignedShortType());

		if (showBackground) {
			ImageJFunctions.show(shading, "Shading");
		}
		ImageJFunctions.show(corrected, "Corrected");

	}

	private static void addKeyListener() {
		System.out.println("Mapped 'Background Correction' to F1.");

        new Thread(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager()
				.addKeyEventDispatcher(e -> {
					if (e.getID() == KeyEvent.KEY_PRESSED) {
						if (e.getKeyCode() == KeyEvent.VK_F1) {
							new ShadingCorrection_Plugin().run(null);
						}
					}
					return false;
				})).start();
	}

	public static void main(final String[] args) throws IOException {
		final Parameters params = new Parameters();
		params.parse(args);

		new ImageJ();
		SwingUtilities.invokeLater(ShadingCorrection_Plugin::addKeyListener);

		IJ.log("Opening " + params.renderWebService.owner + "/" + params.renderWebService.project + "/" + params.stack);
		IJ.log("Showing slice " + params.z);

		final ImagePlus img = renderImage(params);
		img.show();
	}

	private static ImagePlus renderImage(final Parameters params) throws IOException {
		final RenderDataClient client = params.renderWebService.getDataClient();
		final Bounds stackBounds = client.getStackMetaData(params.stack).getStackBounds();
		final long x = stackBounds.getMinX().longValue();
		final long y = stackBounds.getMinY().longValue();
		final long w = stackBounds.getWidth();
		final long h = stackBounds.getHeight();

		if (params.scale == 0.0) {
			// automatically determine downscale factor
			IJ.log("No scale given, choosing automatically...");
			params.scale = Math.min(1, Math.sqrt((double) MAX_SIZE / (w * h)));
		}

		if (params.scale == 1) {
			IJ.log("No downscaling, showing original image at full size.");
		} else {
			IJ.log("Isotropically downscaling by factor of " + params.scale);
		}

		final ImageProcessorWithMasks ipwm = RenderTools.renderImage(ImageProcessorCache.DISABLED_CACHE,
																	 params.renderWebService.baseDataUrl,
																	 params.renderWebService.owner,
																	 params.renderWebService.project,
																	 params.stack,
																	 x, y, params.z, w, h,
																	 params.scale, false);

		final String title = params.renderWebService.project + "-" + params.stack + "(z=" + params.z + ")";
		return new ImagePlus(title, ipwm.ip);
	}
}
