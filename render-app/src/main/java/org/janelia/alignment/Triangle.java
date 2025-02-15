package org.janelia.alignment;

import static org.janelia.alignment.Triangle.WindingOrder.CCW;

/**
 * A triangle in 2D, comprising points A, B, C specified by {@code ax, ay,
 * bx, by, cx, cy}. The points are stored in counter-clockwise order (the
 * constructor re-orders points if necessary).
 * <p>
 * When intersecting horizontal scan-lines with a triangle edge, the points
 * {@code (a, b)} of the edge are always re-ordered such that {@code ay <=
 * by}. This is to prevent potential gaps between triangles introduced by
 * rounding errors. When two neighboring triangles contain the same edge
 * (same points in the same order), an X position on a horizontal line will
 * never be between triangles.
 */
class Triangle {

    private final double ax;
    private final double ay;
    private final double bx;
    private final double by;
    private final double cx;
    private final double cy;

    enum WindingOrder {
        CW, CCW;

        static WindingOrder of(final double ax, final double ay, final double bx, final double by, final double cx, final double cy) {
            final double P = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            return P > 0 ? CW : CCW;
        }
    }

    Triangle(final double ax, final double ay, final double bx, final double by, final double cx, final double cy) {
        if (WindingOrder.of(ax, ay, bx, by, cx, cy) == CCW) {
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
        } else {
            this.ax = bx;
            this.ay = by;
            this.bx = ax;
            this.by = ay;
        }
        this.cx = cx;
        this.cy = cy;
    }

    @Override
    public String toString() {
        return "Triangle{" +
               "A=(" + ax + ", " + ay + "), " +
               "B=(" + bx + ", " + by + "), " +
               "C=(" + cx + ", " + cy + ")" +
               '}';
    }

    static class Range {
        private final int from;
        private final int length;

        private Range(final int min, final int max) {
            this.from = min;
            this.length = max - min;
        }

        /**
         * min (inclusive) of this Range.
         */
        int from() {
            return from;
        }

        /**
         * number of elements in this Range.
         */
        int length() {
            return length;
        }
    }

    /**
     * Compute the intersection of horizontal scan-line at {@code y} with this triangle.
     * The resulting discrete X range can is [{@code intersectionMinX(), intersectionMaxX()})
     *
     * @param y         Y coordinate of scanline to intersect with this triangle
     * @param rangeMinX inclusive lower bound (minimum discrete X coordinate to consider)
     * @param rangeMaxX exclusive upper bound (maximum discrete X coordinate to consider + 1)
     * @return the discrete X range of the intersected scanline.
     */
    Range intersect(final double y, final int rangeMinX, final int rangeMaxX) {
        final double[] bounds = new double[]{rangeMinX, rangeMaxX};
        intersect(y, bounds);
        return new Range((int) Math.ceil(bounds[0]), Math.min(rangeMaxX, (int) Math.floor(bounds[1] + 1)));
    }

    void intersect(final double y, final double[] bounds) {
        updateRange(ax, ay, bx, by, y, bounds);
        updateRange(bx, by, cx, cy, y, bounds);
        updateRange(cx, cy, ax, ay, y, bounds);
    }

    private static void updateRange(final double ax, final double ay, final double bx, final double by, final double y, final double[] bounds) {
        final boolean flip = ay > by;
        final double px = flip ? ax : bx;
        final double qx = flip ? bx : ax;
        final double py = flip ? ay : by;
        final double qy = flip ? by : ay;

        final double s = (qx - px) / (qy - py);
        if (Double.isInfinite(s)) {
            return;
        } else if (Double.isNaN(s)) {
            throw new IllegalArgumentException("zero length triangle edge");
        }

        final double x = px + s * (y - py);
        if (flip) {
            bounds[1] = Math.min(bounds[1], x);
        } else {
            bounds[0] = Math.max(bounds[0], x);
        }
    }
}
