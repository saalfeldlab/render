package org.janelia.render.client.emshading;

import ij.ImageJ;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.alignment.filter.emshading.FourthOrderShading;
import org.janelia.alignment.filter.emshading.ShadingModel;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CorrectShading {

	public static void main(final String[] args) throws NotEnoughDataPointsException, IllDefinedDataPointsException, IOException {

		final String containerPath = System.getenv("HOME") + "/big-data/render-exports/cerebellum-3.n5";
		final String roiPath = containerPath + "/roi-set.zip";
		final String dataset = "data";
		final int scale = 4;

		try (final N5Reader reader = new N5FSReader(containerPath)) {
			final Img<UnsignedByteType> stack = N5Utils.open(reader, dataset + "/s" + scale);
			final RandomAccessibleInterval<UnsignedByteType> firstSlice = Views.hyperSlice(stack, 2, 0);
			final List<Roi> rois = readRois(roiPath);

			if (rois.isEmpty()) {
				throw new IllegalArgumentException("No ROIs found");
			}

			final long start = System.currentTimeMillis();
			final ShadingModel shadingModel = new FourthOrderShading();
			fitBackgroundModel(rois, firstSlice, shadingModel);
			System.out.println("Fitted shading model: " + shadingModel);
			System.out.println("Fitting took " + (System.currentTimeMillis() - start) + "ms.");

			final RandomAccessibleInterval<FloatType> shading = createBackgroundImage(shadingModel, firstSlice);
			final RandomAccessibleInterval<UnsignedByteType> corrected = correctBackground(firstSlice, shading, new UnsignedByteType());

			new ImageJ();
			ImageJFunctions.show(firstSlice, "Original");
			ImageJFunctions.show(shading, "Shading");
			ImageJFunctions.show(corrected, "Corrected");
		}
	}

	public static <T extends NativeType<T> & RealType<T>>
	void fitBackgroundModel(final List<Roi> rois, final RandomAccessibleInterval<T> slice, final ShadingModel shadingModel)
			throws NotEnoughDataPointsException, IllDefinedDataPointsException {

		// transform pixel coordinates into [-1, 1] x [-1, 1]
		final double dimX = slice.dimension(0);
		final double dimY = slice.dimension(1);

		// fit a quadratic shading model with all the points in the first slice
		final List<PointMatch> matches = new ArrayList<>();
		final RandomAccess<T> ra = slice.randomAccess();

		for (final java.awt.Point point : extractInterestPoints(rois)) {
			final double x = ShadingModel.toModelCoordinates(point.x, 0, dimX);
			final double y = ShadingModel.toModelCoordinates(point.y, 0, dimY);
			final double z = ra.setPositionAndGet(point.x, point.y).getRealDouble();
			matches.add(new PointMatch(new Point(new double[]{x, y}), new Point(new double[]{z})));
		}
		shadingModel.fit(matches);
		shadingModel.normalize();
	}

	private static Set<java.awt.Point> extractInterestPoints(final List<Roi> rois) {

		final Set<java.awt.Point> pointsOfInterest = new HashSet<>();
		for (final Roi roi : rois) {
			final java.awt.Point[] containedPoints = roi.getContainedPoints();
			Collections.addAll(pointsOfInterest, containedPoints);
		}
		return pointsOfInterest;
	}

	public static <T extends NativeType<T> & RealType<T>>
	RandomAccessibleInterval<FloatType> createBackgroundImage(final ShadingModel shadingModel, final RandomAccessibleInterval<T> slice) {

		final double dimX = slice.dimension(0);
		final double dimY = slice.dimension(1);

		final double[] location = new double[2];
		final RealRandomAccessible<FloatType> shading = new FunctionRealRandomAccessible<>(2, (pos, value) -> {
			location[0] = ShadingModel.toModelCoordinates(pos.getDoublePosition(0), 0, dimX);
			location[1] = ShadingModel.toModelCoordinates(pos.getDoublePosition(1), 0, dimY);
			shadingModel.applyInPlace(location);
			value.setReal(location[0]);
		}, FloatType::new);

		return Views.interval(Views.raster(shading), slice);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static <T extends NativeType<T> & RealType<T>>
	RandomAccessibleInterval<T> correctBackground(final RandomAccessibleInterval<T> slice, final RandomAccessibleInterval<FloatType> shading, final T type) {

		final RandomAccessibleInterval<T> corrected;
		if (type instanceof UnsignedByteType) {
			corrected = (RandomAccessibleInterval) Converters.convert(slice, shading, (s, b, o) -> {
				o.set(UnsignedByteType.getCodedSignedByteChecked((int) (s.getRealDouble() - b.getRealDouble())));
			}, new UnsignedByteType());
		} else if (type instanceof UnsignedShortType) {
			corrected = (RandomAccessibleInterval) Converters.convert(slice, shading, (s, b, o) -> {
				o.set(UnsignedShortType.getCodedSignedShortChecked((int) (s.getRealDouble() - b.getRealDouble())));
			}, new UnsignedShortType());
		} else {
			throw new IllegalArgumentException("Unsupported type: " + type.getClass());
		}

		return corrected;
	}

	public static List<Roi> readRois(final String path) throws IOException {
		if (path.endsWith(".zip")) {
			return extractRoisFromZip(path);
		} else {
			final RoiDecoder rd = new RoiDecoder(path);
			final Roi roi = rd.getRoi();
			return List.of(roi);
		}
	}

	private static List<Roi> extractRoisFromZip(final String path) throws IOException {
		final List<Roi> rois = new ArrayList<>();

		try (final ZipFile zipFile = new ZipFile(path)) {
			final Enumeration<? extends ZipEntry> entries = zipFile.entries();

			while (entries.hasMoreElements()) {
				final ZipEntry entry = entries.nextElement();
				final String name = entry.getName();

				if (name.endsWith(".roi")) {
					final RoiDecoder rd = new RoiDecoder(zipFile.getInputStream(entry).readAllBytes(), entry.getName());
					final Roi roi = rd.getRoi();
					if (roi != null) {
						rois.add(roi);
					}
				}
			}
		}

		return rois;
	}
}
