package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a constant offset to all pixels inside a set of polygons (pixels covered by several polygons are only
 * offset once). The polygon vertices are given relative to the image size (i.e., in [0, 1] for points within the
 * image), so that the same filter can be applied at every mipmap level.
 * <p>
 * The polygons are serialized as a string of the form {@code x0,y0,x1,y1,...;x0,y0,...}.
 */
public class PolygonIntensityOffsetFilter implements Filter {

    private double offset;
    private List<double[]> polygons;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public PolygonIntensityOffsetFilter() {
        this(0.0, new ArrayList<>());
    }

    /**
     * @param  offset    value to add to the pixels inside the polygons.
     * @param  polygons  polygons as flat arrays of relative (x, y) vertex coordinates.
     */
    public PolygonIntensityOffsetFilter(final double offset,
                                        final List<double[]> polygons) {
        this.offset = offset;
        this.polygons = polygons;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.offset = Filter.getDoubleParameter("offset", params);
        this.polygons = new ArrayList<>();
        for (final String polygon : Filter.getStringParameter("polygons", params).split(";")) {
            if (! polygon.isEmpty()) {
                final String[] values = polygon.split(",");
                final double[] vertices = new double[values.length];
                for (int i = 0; i < values.length; i++) {
                    vertices[i] = Double.parseDouble(values[i]);
                }
                this.polygons.add(vertices);
            }
        }
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("offset", String.valueOf(offset));
        final StringBuilder polygonString = new StringBuilder();
        for (final double[] vertices : polygons) {
            if (polygonString.length() > 0) {
                polygonString.append(';');
            }
            for (int i = 0; i < vertices.length; i++) {
                polygonString.append(i == 0 ? "" : ",").append((float) vertices[i]);
            }
        }
        map.put("polygons", polygonString.toString());
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        final int width = ip.getWidth();
        final int height = ip.getHeight();

        final List<Path2D> paths = new ArrayList<>();
        Rectangle2D bounds = null;
        for (final double[] vertices : polygons) {
            final Path2D path = new Path2D.Double();
            path.moveTo(vertices[0] * width, vertices[1] * height);
            for (int i = 2; i < vertices.length; i += 2) {
                path.lineTo(vertices[i] * width, vertices[i + 1] * height);
            }
            path.closePath();
            bounds = (bounds == null) ? path.getBounds2D() : bounds.createUnion(path.getBounds2D());
            paths.add(path);
        }
        if (bounds == null) {
            return;
        }

        final int minX = Math.max(0, (int) Math.floor(bounds.getMinX()));
        final int maxX = Math.min(width, (int) Math.ceil(bounds.getMaxX()));
        final int minY = Math.max(0, (int) Math.floor(bounds.getMinY()));
        final int maxY = Math.min(height, (int) Math.ceil(bounds.getMaxY()));
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                if (isInside(paths, x + 0.5, y + 0.5)) {
                    // putPixelValue clamps to the range of integer types
                    ip.putPixelValue(x, y, ip.getPixelValue(x, y) + offset);
                }
            }
        }
    }

    private static boolean isInside(final List<Path2D> paths, final double x, final double y) {
        for (final Path2D path : paths) {
            if (path.contains(x, y)) {
                return true;
            }
        }
        return false;
    }
}
