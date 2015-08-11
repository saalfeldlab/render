/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.trakem2.util.Pair;
import mpicbg.util.Util;

/**
 * Specialized {@link mpicbg.ij.TransformMapping} for Patches, that is,
 * rendering the image, outside mask and mask in one go instead three.
 *
 */
public class RenderTransformMeshMappingWithMasks {

    final protected RenderTransformMesh transform;

    public RenderTransformMeshMappingWithMasks(final RenderTransformMesh transform) {
        this.transform = transform;
    }

    final static private class MapTriangleThread extends Thread {
        final private AtomicInteger i;
        final private List<Pair<AffineModel2D, double[][]>> triangles;
        final ImageProcessorWithMasks source, target;

        MapTriangleThread(final AtomicInteger i, final List<Pair<AffineModel2D, double[][]>> triangles, final ImageProcessorWithMasks source,
                final ImageProcessorWithMasks target) {
            this.i = i;
            this.triangles = triangles;
            this.source = source;
            this.target = target;
        }

        @Override
        final public void run() {
            int k = i.getAndIncrement();
            while (!isInterrupted() && k < triangles.size()) {
                if (source.mask == null)
                    mapTriangle(triangles.get(k), source.ip, target.ip, target.outside);
                else
                    mapTriangle(triangles.get(k), source.ip, source.mask, target.ip, target.mask, target.outside);
                k = i.getAndIncrement();
            }
        }
    }

    final static private class MapTriangleInterpolatedThread extends Thread {
        final private AtomicInteger i;
        final private List<Pair<AffineModel2D, double[][]>> triangles;
        final ImageProcessorWithMasks source, target;

        MapTriangleInterpolatedThread(final AtomicInteger i, final List<Pair<AffineModel2D, double[][]>> triangles, final ImageProcessorWithMasks source,
                final ImageProcessorWithMasks target) {
            this.i = i;
            this.triangles = triangles;
            this.source = source;
            this.target = target;
        }

        @Override
        final public void run() {
            int k = i.getAndIncrement();
            while (!isInterrupted() && k < triangles.size()) {
                if (source.mask == null)
                    mapTriangleInterpolated(triangles.get(k), source.ip, target.ip, target.outside);
                else
                    mapTriangleInterpolated(triangles.get(k), source.ip, source.mask, target.ip, target.mask, target.outside);
                k = i.getAndIncrement();
            }
        }
    }

    final static private class MapShortAlphaTriangleThread extends Thread {
        final private AtomicInteger i;
        final private List<Pair<AffineModel2D, double[][]>> triangles;
        final ShortProcessor source, target;
        final ByteProcessor alpha;

        MapShortAlphaTriangleThread(final AtomicInteger i, final List<Pair<AffineModel2D, double[][]>> triangles, final ShortProcessor source,
                final ByteProcessor alpha, final ShortProcessor target) {
            this.i = i;
            this.triangles = triangles;
            this.source = source;
            this.alpha = alpha;
            this.target = target;
        }

        @Override
        final public void run() {
            int k = i.getAndIncrement();
            while (!isInterrupted() && k < triangles.size()) {
                mapShortAlphaTriangle(triangles.get(k), source, alpha, target);
                k = i.getAndIncrement();
            }
        }
    }

    final static protected void mapTriangle(final Pair<AffineModel2D, double[][]> ai, final ImageProcessor source, final ImageProcessor target,
            final ByteProcessor targetOutside) {

        final int w = target.getWidth() - 1;
        final int h = target.getHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] t = new double[2];
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                if (RenderTransformMesh.isInTargetTriangle(pq, t)) {
                    t[0] = x;
                    t[1] = y;
                    try {
                        ai.a.applyInverseInPlace(t);
                    } catch (final Exception e) {
                        // e.printStackTrace( System.err );
                        continue;
                    }
                    target.set(x, y, source.getPixel((int) (t[0] + 0.5f), (int) (t[1] + 0.5f)));
                    targetOutside.set(x, y, 0xff);
                }
            }
        }
    }

    final static protected void mapTriangleInterpolated(final Pair<AffineModel2D, double[][]> ai, final ImageProcessor source, final ImageProcessor target,
            final ByteProcessor targetOutside) {

        final int w = target.getWidth() - 1;
        final int h = target.getHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] t = new double[2];
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                if (RenderTransformMesh.isInTargetTriangle(pq, t)) {
                    t[0] = x;
                    t[1] = y;
                    try {
                        ai.a.applyInverseInPlace(t);
                    } catch (final Exception e) {
                        // e.printStackTrace( System.err );
                        continue;
                    }
                    target.set(x, y, source.getPixelInterpolated(t[0], t[1]));
                    targetOutside.set(x, y, 0xff);
                }
            }
        }
    }

    final static protected void mapTriangle(final Pair<AffineModel2D, double[][]> ai, final ImageProcessor source, final ImageProcessor sourceMask,
            final ImageProcessor target, final ImageProcessor targetMask, final ByteProcessor targetOutside) {

        final int w = target.getWidth() - 1;
        final int h = target.getHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] t = new double[2];
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                if (RenderTransformMesh.isInTargetTriangle(pq, t)) {
                    t[0] = x;
                    t[1] = y;
                    try {
                        ai.a.applyInverseInPlace(t);
                    } catch (final Exception e) {
                        // e.printStackTrace( System.err );
                        continue;
                    }
                    target.set(x, y, source.getPixel((int) (t[0] + 0.5f), (int) (t[1] + 0.5f)));
                    targetOutside.set(x, y, 0xff);
                    targetMask.set(x, y, sourceMask.getPixel((int) (t[0] + 0.5f), (int) (t[1] + 0.5f)));
                }
            }
        }
    }

    final static protected void mapTriangleInterpolated(final Pair<AffineModel2D, double[][]> ai, final ImageProcessor source, final ImageProcessor sourceMask,
            final ImageProcessor target, final ImageProcessor targetMask, final ByteProcessor targetOutside) {
        final int w = target.getWidth() - 1;
        final int h = target.getHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] t = new double[2];
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                if (RenderTransformMesh.isInTargetTriangle(pq, t)) {
                    t[0] = x;
                    t[1] = y;
                    try {
                        ai.a.applyInverseInPlace(t);
                    } catch (final Exception e) {
                        // e.printStackTrace( System.err );
                        continue;
                    }
                    target.set(x, y, source.getPixelInterpolated(t[0], t[1]));
                    targetOutside.set(x, y, 0xff);
                    targetMask.set(x, y, sourceMask.getPixelInterpolated(t[0], t[1]));
                }
            }
        }
    }

    final static protected void mapShortAlphaTriangle(final Pair<AffineModel2D, double[][]> ai, final ShortProcessor source, final ByteProcessor alpha,
            final ShortProcessor target) {
        final int w = target.getWidth() - 1;
        final int h = target.getHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] t = new double[2];
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                if (RenderTransformMesh.isInTargetTriangle(pq, t)) {
                    t[0] = x;
                    t[1] = y;
                    try {
                        ai.a.applyInverseInPlace(t);
                    } catch (final Exception e) {
                        // e.printStackTrace( System.err );
                        continue;
                    }
                    final int is = source.getPixelInterpolated(t[0], t[1]);
                    final int it = target.get(x, y);
                    final double f = alpha.getPixelInterpolated(t[0], t[1]) / 255.0;
                    final double v = it + f * (is - it);
                    target.set(x, y, (int) Math.max(0, Math.min(65535, Math.round(v))));
                }
            }
        }
    }

    final public void map(final ImageProcessorWithMasks source, final ImageProcessorWithMasks target, final int numThreads) {
        target.outside = new ByteProcessor(target.getWidth(), target.getHeight());
        final ArrayList<Pair<AffineModel2D, double[][]>> av = transform.getAV();
        if (numThreads > 1) {
            final AtomicInteger i = new AtomicInteger(0);
            final ArrayList<Thread> threads = new ArrayList<Thread>(numThreads);
            for (int k = 0; k < numThreads; ++k) {
                final Thread mtt = new MapTriangleThread(i, av, source, target);
                threads.add(mtt);
                mtt.start();
            }
            for (final Thread mtt : threads) {
                try {
                    mtt.join();
                } catch (final InterruptedException e) {
                }
            }
        } else if (source.mask == null) {
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                mapTriangle(triangle, source.ip, target.ip, target.outside);
            }
        } else {
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                mapTriangle(triangle, source.ip, source.mask, target.ip, target.mask, target.outside);
            }
        }
    }

    final public void mapInterpolated(final ImageProcessorWithMasks source, final ImageProcessorWithMasks target, final int numThreads) {
        target.outside = new ByteProcessor(target.getWidth(), target.getHeight());
        source.ip.setInterpolationMethod(ImageProcessor.BILINEAR);
        if (source.mask != null) {
            source.mask.setInterpolationMethod(ImageProcessor.BILINEAR);
        }
        final ArrayList<Pair<AffineModel2D, double[][]>> av = transform.getAV();
        if (numThreads > 1) {
            final AtomicInteger i = new AtomicInteger(0);
            final ArrayList<Thread> threads = new ArrayList<Thread>(numThreads);
            for (int k = 0; k < numThreads; ++k) {
                final Thread mtt = new MapTriangleInterpolatedThread(i, av, source, target);
                threads.add(mtt);
                mtt.start();
            }
            for (final Thread mtt : threads) {
                try {
                    mtt.join();
                } catch (final InterruptedException e) {
                }
            }
        } else if (source.mask == null) {
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                mapTriangleInterpolated(triangle, source.ip, target.ip, target.outside);
            }
        } else {
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                mapTriangleInterpolated(triangle, source.ip, source.mask, target.ip, target.mask, target.outside);
            }
        }
    }

    final public void map(final ImageProcessorWithMasks source, final ImageProcessorWithMasks target) {
        map(source, target, Runtime.getRuntime().availableProcessors());
    }

    final public void mapInterpolated(final ImageProcessorWithMasks source, final ImageProcessorWithMasks target) {
        mapInterpolated(source, target, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Render source into target using alpha composition. Interpolation is
     * specified by the interpolation methods set in source and alpha.
     *
     * @param source
     * @param alpha
     * @param target
     * @param numThreads
     */
    final public void map(final ShortProcessor source, final ByteProcessor alpha, final ShortProcessor target, final int numThreads) {
        final AtomicInteger i = new AtomicInteger(0);
        final ArrayList<Thread> threads = new ArrayList<Thread>(numThreads);
        for (int k = 0; k < numThreads; ++k) {
            final Thread mtt = new MapShortAlphaTriangleThread(i, transform.getAV(), source, alpha, target);
            threads.add(mtt);
            mtt.start();
        }
        for (final Thread mtt : threads) {
            try {
                mtt.join();
            } catch (final InterruptedException e) {
            }
        }
    }

    /**
     * Render source into master using alpha composition. Interpolation is
     * specified by the interpolation methods set in source and alpha.
     *
     * @param source
     * @param alpha
     * @param target
     */
    final public void map(final ShortProcessor source, final ByteProcessor alpha, final ShortProcessor target) {
        map(source, alpha, target, Runtime.getRuntime().availableProcessors());
    }
}
