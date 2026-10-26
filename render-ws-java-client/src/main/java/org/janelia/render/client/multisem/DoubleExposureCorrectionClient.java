package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.filter.CompositeFilter;
import org.janelia.alignment.filter.Filter;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.PolygonIntensityOffsetFilter;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that compensates the double exposure of Multi-SEM MFOV overlaps.
 * <p>
 * Neighboring MFOVs overlap by about one row of tiles. Assuming that MFOVs are acquired in the order of their
 * number, the overlap is exposed once when the lower-numbered MFOV is acquired and imaged a second time in the
 * higher-numbered MFOV, where it appears a few gray levels lighter (with a sharp step at the edge of the
 * lower-numbered MFOV). For each tile, this client finds all world-space footprints of tiles of lower-numbered
 * MFOVs in the same layer, maps them into the tile's local (raw image) coordinates, and attaches a
 * {@link PolygonIntensityOffsetFilter} that adds the specified offset to the covered pixels. The filtered specs
 * are written to a new derived stack and the original images are not modified.
 *
 * @author Michael Innerberger
 */
public class DoubleExposureCorrectionClient {

	public static class Parameters extends CommandLineParameters {

		@ParametersDelegate
		public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

		@Parameter(names = "--stack", description = "Source stack to correct", required = true)
		public String stack;

		@Parameter(names = "--targetStack", description = "Target stack for the corrected tile specs", required = true)
		public String targetStack;

		@Parameter(names = "--offset", description = "Gray value offset to add to the double-exposed pixels")
		public double offset = -3.0;

		@Parameter(names = "--boundarySampling", description = "Distance (in raw pixels) between the sampled boundary points of a tile footprint")
		public double boundarySampling = 50.0;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {
				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);
				correctStack(parameters.renderWeb.getDataClient(), parameters);
			}
		};
		clientRunner.run();
	}

	public static void correctStack(final RenderDataClient dataClient,
									final Parameters parameters) throws IOException {

		final StackMetaData sourceStackMetaData = dataClient.getStackMetaData(parameters.stack);
		dataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

		int correctedCount = 0;
		for (final Double z : dataClient.getStackZValues(parameters.stack)) {
			final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(parameters.stack, z);
			final List<TileSpec> tileSpecs = new ArrayList<>(resolvedTiles.getTileSpecs());

			// compute all polygons before changing any tile spec
			final List<List<double[]>> allPolygons = new ArrayList<>();
			for (final TileSpec tileSpec : tileSpecs) {
				allPolygons.add(findDoubleExposedPolygons(tileSpec, tileSpecs, parameters.boundarySampling));
			}

			for (int i = 0; i < tileSpecs.size(); i++) {
				if (allPolygons.get(i).isEmpty()) {
					continue;
				}
				final TileSpec tileSpec = tileSpecs.get(i);
				Filter filter = new PolygonIntensityOffsetFilter(parameters.offset, allPolygons.get(i));
				final FilterSpec existingFilterSpec = tileSpec.getFilterSpec();
				if (existingFilterSpec != null) {
					// the double exposure is a property of the raw image, so correct it first
					filter = new CompositeFilter(filter, existingFilterSpec.buildInstance());
				}
				tileSpec.setFilterSpec(FilterSpec.forFilter(filter));
				tileSpec.convertSingleChannelSpecToLegacyForm();
				correctedCount++;
			}

			dataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, z);
			LOG.info("correctStack: saved z {} to {}", z, parameters.targetStack);
		}

		dataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
		LOG.info("correctStack: exit, added double exposure correction to {} tiles", correctedCount);
	}

	/**
	 * @return footprints of all tiles of lower-numbered MFOVs that overlap the given tile, as polygons in the
	 *         tile's relative local coordinates (see {@link PolygonIntensityOffsetFilter}).
	 */
	public static List<double[]> findDoubleExposedPolygons(final TileSpec tile,
													final List<TileSpec> layerTiles,
													final double boundarySampling) {
		// ponytail: all tiles of a layer are assumed to come from the same scan, so only MFOV numbers are compared
		final int mfov = getMfovNumber(tile);
		final Area exposedBefore = new Area();
		for (final TileSpec other : layerTiles) {
			if (getMfovNumber(other) < mfov && boundingBoxesOverlap(tile, other)) {
				exposedBefore.add(toWorldArea(other, boundarySampling));
			}
		}
		final List<double[]> polygons = new ArrayList<>();
		if (exposedBefore.isEmpty()) {
			return polygons;
		}

		// clip in world space since the tile's transforms cannot be inverted far outside the tile
		exposedBefore.intersect(toWorldArea(tile, boundarySampling));
		final List<Double> vertices = new ArrayList<>();
		final double[] segment = new double[6];
		for (final PathIterator it = exposedBefore.getPathIterator(null); ! it.isDone(); it.next()) {
			final int type = it.currentSegment(segment);
			if (type == PathIterator.SEG_CLOSE) {
				polygons.add(vertices.stream().mapToDouble(Double::doubleValue).toArray());
				vertices.clear();
			} else {
				// Area only contains straight segments for polygonal input
				final double[] local = worldToLocal(tile, segment[0], segment[1]);
				vertices.add(local[0] / tile.getWidth());
				vertices.add(local[1] / tile.getHeight());
			}
		}
		return polygons;
	}

	private static Area toWorldArea(final TileSpec tile, final double sampling) {
		final Path2D path = new Path2D.Double();
		for (final double[] point : sampleBoundary(tile, sampling)) {
			if (path.getCurrentPoint() == null) {
				path.moveTo(point[0], point[1]);
			} else {
				path.lineTo(point[0], point[1]);
			}
		}
		path.closePath();
		return new Area(path);
	}

	private static int getMfovNumber(final TileSpec tileSpec) {
		return Integer.parseInt(MultiSemUtilities.getSimpleMfovForTileId(tileSpec.getTileId()).substring(1));
	}

	private static boolean boundingBoxesOverlap(final TileSpec a, final TileSpec b) {
		return a.getMinX() < b.getMaxX() && b.getMinX() < a.getMaxX()
				&& a.getMinY() < b.getMaxY() && b.getMinY() < a.getMaxY();
	}

	/** @return world coordinates of points sampled along the boundary of the tile's raw image (clockwise). */
	private static List<double[]> sampleBoundary(final TileSpec tile, final double sampling) {
		final double w = tile.getWidth();
		final double h = tile.getHeight();
		final double[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
		final List<double[]> boundary = new ArrayList<>();
		for (int c = 0; c < 4; c++) {
			final double[] from = corners[c];
			final double[] to = corners[(c + 1) % 4];
			final int n = (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1]) / sampling);
			for (int k = 0; k < n; k++) {
				final double t = (double) k / n;
				boundary.add(tile.getWorldCoordinates(from[0] + t * (to[0] - from[0]), from[1] + t * (to[1] - from[1])));
			}
		}
		return boundary;
	}

	/**
	 * Inverts the tile's transforms with a fixed-point iteration that uses the Jacobian at the tile center.
	 * Only reliable for points within (or close to) the tile, since the lens correction diverges far outside of it.
	 */
	public static double[] worldToLocal(final TileSpec tile, final double worldX, final double worldY) {
		final double cx = tile.getWidth() / 2.0;
		final double cy = tile.getHeight() / 2.0;
		final double[] c = tile.getWorldCoordinates(cx, cy);
		final double[] dx = tile.getWorldCoordinates(cx + 1, cy);
		final double[] dy = tile.getWorldCoordinates(cx, cy + 1);
		final double a = dx[0] - c[0], b = dy[0] - c[0];
		final double d = dx[1] - c[1], e = dy[1] - c[1];
		final double det = a * e - b * d;

		double x = cx, y = cy;
		for (int iteration = 0; iteration < 100; iteration++) {
			final double[] world = tile.getWorldCoordinates(x, y);
			final double rx = worldX - world[0];
			final double ry = worldY - world[1];
			x += (e * rx - b * ry) / det;
			y += (a * ry - d * rx) / det;
			if (Math.hypot(rx, ry) < 1e-3) {
				break;
			}
		}
		return new double[]{x, y};
	}

	private static final Logger LOG = LoggerFactory.getLogger(DoubleExposureCorrectionClient.class);
}
