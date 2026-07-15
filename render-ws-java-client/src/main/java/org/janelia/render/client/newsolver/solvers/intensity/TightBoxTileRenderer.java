package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;

import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.trakem2.util.Downsampler;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorUtil;
import org.janelia.render.client.solver.visualize.VisualizeTools;

/**
 * {@link TileRenderer} that renders exactly the requested box on every call, but caches the
 * pair-independent part of the render: the filtered, downsampled source.
 * <p>
 * Caching uses a {@link TileCache} bounded by (downsampled) pixel number, which is instance-scoped.
 */
class TightBoxTileRenderer implements TileRenderer {

	private final int numCoefficients;
	private final double scale;
	private final int meshResolution;
	private final TileCache<DownsampledSource> sourceCache;

	/**
	 * @param numCoefficients number of coefficients in the intensity model
	 * @param scale render scale
	 * @param meshResolution resolution of the mesh used to render the box
	 * @param maximumCachedKilobytes maximum size of the cache for downsampled sources
	 */
	TightBoxTileRenderer(final int numCoefficients,
						 final double scale,
						 final int meshResolution,
						 final long maximumCachedKilobytes) {
		this.numCoefficients = numCoefficients;
		this.scale = scale;
		this.meshResolution = meshResolution;
		this.sourceCache = new TileCache<>(
				maximumCachedKilobytes,
				source -> (int) Math.min(Integer.MAX_VALUE, source.kilobytes()),
				this::loadSource);
	}

	@Override
	public RenderedTile render(final TileSpec patch, final Rectangle box) {
		final int w = (int) Math.round(box.width * scale);
		final int h = (int) Math.round(box.height * scale);

		final DownsampledSource source = sourceCache.get(patch);

		final FloatProcessor image = new FloatProcessor(w, h);
		final FloatProcessor weight = new FloatProcessor(w, h);
		final ColorProcessor coefficients = new ColorProcessor(w, h);
		renderBox(patch, source, box.x, box.y, image, weight, coefficients);

		// wrap the just-rendered pixel arrays as image views (zero-copy)
		final RandomAccessibleInterval<FloatType> imageView = ArrayImgs.floats((float[]) image.getPixels(), w, h);
		final RandomAccessibleInterval<FloatType> weightView = ArrayImgs.floats((float[]) weight.getPixels(), w, h);
		final RandomAccessibleInterval<IntType> coefficientsView = ArrayImgs.ints((int[]) coefficients.getPixels(), w, h);

		return new RenderedTile(imageView, weightView, coefficientsView);
	}

	/**
	 * Pair-independent part of the render: load the full-resolution source, apply the tile's filter
	 * and downsample to the mipmap level. The result is cached per tile.
	 */
	private DownsampledSource loadSource(final TileSpec patch) {

		// Get the entire image at full scale; the per-tile cache below already dedups loads across
		// pairs, so the full-resolution image processor cache would add nothing but memory pressure
		final ImageProcessorWithMasks impOriginal =
				VisualizeTools.getUntransformedProcessorWithMasks(patch, ImageProcessorCache.DISABLED_CACHE);

		// Apply filters if there are any
		final FilterSpec filterSpec = patch.getFilterSpec();
		if (filterSpec != null) {
			filterSpec.buildInstance().process(impOriginal.ip, scale);
		}

		final int mipmapLevel = getMipmapLevel(patch);
		final ImageProcessor ipMipmap = Downsampler.downsampleImageProcessor(impOriginal.ip, mipmapLevel);

		final ByteProcessor bpMask = (ByteProcessor) impOriginal.mask;
		final ByteProcessor bpMaskMipmap = (bpMask == null) ? null : Downsampler.downsampleByteProcessor(bpMask, mipmapLevel);

		return new DownsampledSource(ipMipmap, bpMaskMipmap, mipmapLevel);
	}

	private int getMipmapLevel(final TileSpec patch) {
		// Transform the mesh nodes with the tile spec's transforms
		final CoordinateTransformList<CoordinateTransform> ctl = patch.getTransformList();
		final AffineModel2D affineScale = new AffineModel2D();
		affineScale.set(scale, 0, 0, scale, 0, 0);
		ctl.add(affineScale);
		final int width = patch.getWidth(), height = patch.getHeight();
		final ArrayList<PointMatch> samples = new ArrayList<>();

		for (double y = 0; y < height; y += (double) width / meshResolution) {
			for (double x = 0; x < width; x += (double) width / meshResolution) {
				final Point p = new Point(new double[]{x, y});
				p.apply(ctl);
				samples.add(new PointMatch(p, p));
			}
		}

		// Fit a similarity model to the mesh nodes to get the average scale
		double s;
		final SimilarityModel2D model = new SimilarityModel2D();
		try {
			model.fit(samples);
			final double[] data = new double[6];
			model.toArray(data);
			s = Math.sqrt(data[0] * data[0] + data[1] * data[1]);
		} catch (final NotEnoughDataPointsException e) {
			e.printStackTrace(System.err);
			s = 1;
		}

		// Find the best mipmap scale
		int invScale = (int) (1.0 / s);
		int scaleLevel = 0;
		while (invScale > 1) {
			invScale >>= 1;
			++scaleLevel;
		}
		return scaleLevel;
	}

	/**
	 * Box-dependent part of the render: mesh the cached source into the requested target box and
	 * convert intensities [0, 255] &rarr; [0, 1] and weights [0, 255] &rarr; [0, 1]. Writes into the
	 * supplied target processors, which define the box dimensions.
	 */
	private void renderBox(final TileSpec patch,
						   final DownsampledSource source,
						   final double x,
						   final double y,
						   final FloatProcessor targetImage,
						   final FloatProcessor targetWeight,
						   final ColorProcessor targetCoefficients) {

		/* assemble coordinate transformations and add bounding box offset */
		final CoordinateTransformList<CoordinateTransform> ctl = patch.getTransformList();
		final AffineModel2D affineScale = new AffineModel2D();
		affineScale.set(scale, 0, 0, scale, -x * scale, -y * scale);
		ctl.add(affineScale);

		final ImageProcessor image = source.image;

		/* create a target */
		final ImageProcessor tp = image.createProcessor(targetImage.getWidth(), targetImage.getHeight());

		/* prepare target for the alpha mask if there is one */
		final ByteProcessor bpMaskMipmap = source.mask;
		final ByteProcessor bpMaskTarget = (bpMaskMipmap == null) ? null : new ByteProcessor(tp.getWidth(), tp.getHeight());

		/* create coefficients map */
		final ColorProcessor cp = new ColorProcessor(image.getWidth(), image.getHeight());
		final int w = cp.getWidth();
		final int h = cp.getHeight();
		for (int yi = 0; yi < h; ++yi) {
			final int yc = yi * numCoefficients / h;
			final int ic = yc * numCoefficients;
			final int iyi = yi * w;
			for (int xi = 0; xi < w; ++xi)
				cp.set(iyi + xi, ic + (xi * numCoefficients / w) + 1);
		}

		/* attach mipmap transformation */
		final CoordinateTransformList<CoordinateTransform> ctlMipmap = new CoordinateTransformList<>();
		ctlMipmap.add(createScaleLevelTransform(source.mipmapLevel));
		ctlMipmap.add(ctl);

		/* create mesh */
		final CoordinateTransformMesh mesh = new CoordinateTransformMesh(ctlMipmap, meshResolution, image.getWidth(), image.getHeight());

		/* render */
		final ImageProcessorWithMasks src = new ImageProcessorWithMasks(image, bpMaskMipmap, null);
		final ImageProcessorWithMasks target = new ImageProcessorWithMasks(tp, bpMaskTarget, null);
		final TransformMeshMappingWithMasks<TransformMesh> mapping = new TransformMeshMappingWithMasks<>(mesh);
		mapping.mapInterpolated(src, target, 1);

		final TransformMeshMapping<TransformMesh> coefficientsMapMapping = new TransformMeshMapping<>(mesh);
		coefficientsMapMapping.map(cp, targetCoefficients, 1);

		/* set alpha channel */
		final byte[] alphaPixels;
		if (bpMaskTarget != null)
			alphaPixels = (byte[]) bpMaskTarget.getPixels();
		else
			alphaPixels = (byte[]) target.outside.getPixels();

		/* convert */
		final double min = 0;
		final double max = 255;
		final double a = 1.0 / (max - min);
		final double b = 1.0 / 255.0;

		for (int i = 0; i < alphaPixels.length; ++i)
			targetImage.setf(i, (float) ((tp.getf(i) - min) * a));

		for (int i = 0; i < alphaPixels.length; ++i)
			targetWeight.setf(i, (float) ((alphaPixels[i] & 0xff) * b));
	}

	/**
	 * Create an affine transformation that compensates for both scale and pixel shift of a mipmap
	 * level that was generated by top-left pixel averaging.
	 */
	private static AffineModel2D createScaleLevelTransform(final int scaleLevel) {
		final AffineModel2D affine = new AffineModel2D();
		final int scale = 1 << scaleLevel;
		final double t = (scale - 1) * 0.5;
		affine.set(scale, 0, 0, scale, t, t);
		return affine;
	}

	/**
	 * The pair-independent, cacheable result of loading, filtering and downsampling a tile: the
	 * downsampled source image, its downsampled alpha mask (or {@code null}), and the mipmap level
	 * they were downsampled to. Treated as read-only once cached, so it can be shared across the
	 * tile's overlap pairs and match threads.
	 */
	private static final class DownsampledSource {
		private final ImageProcessor image;
		private final ByteProcessor mask;
		private final int mipmapLevel;

		private DownsampledSource(final ImageProcessor image, final ByteProcessor mask, final int mipmapLevel) {
			this.image = image;
			this.mask = mask;
			this.mipmapLevel = mipmapLevel;
		}

		private long kilobytes() {
			return ImageProcessorUtil.getKilobytes(image) + (mask == null ? 0 : ImageProcessorUtil.getKilobytes(mask));
		}
	}
}
