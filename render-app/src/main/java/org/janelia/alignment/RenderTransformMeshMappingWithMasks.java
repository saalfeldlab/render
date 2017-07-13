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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.util.Pair;
import mpicbg.util.Util;

import org.janelia.alignment.mapper.PixelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialized {@link mpicbg.ij.TransformMapping} for {@link PixelMapper} instances
 * that calculates transforms once and then applies them to all channels and masks in one go.
 */
public class RenderTransformMeshMappingWithMasks {

    private final RenderTransformMesh transform;

    public RenderTransformMeshMappingWithMasks(final RenderTransformMesh transform) {
        this.transform = transform;
    }

    public final void map(final PixelMapper pixelMapper) {
        map(pixelMapper, Runtime.getRuntime().availableProcessors());
    }

    public final void map(final PixelMapper pixelMapper,
                          final int numThreads) {

        final ArrayList<Pair<AffineModel2D, double[][]>> av = transform.getAV();
        if (numThreads > 1) {
            final AtomicInteger i = new AtomicInteger(0);
            final ArrayList<Thread> threads = new ArrayList<>(numThreads);
            for (int k = 0; k < numThreads; ++k) {
                final Thread mtt = new MapTriangleThread(i, av, pixelMapper);
                threads.add(mtt);
                mtt.start();
            }
            for (final Thread mtt : threads) {
                try {
                    mtt.join();
                } catch (final InterruptedException e) {
                    LOG.warn("ignoring exception", e);
                }
            }
        } else {
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                mapTriangle(triangle, pixelMapper);
            }
        }
    }

    private static final class MapTriangleThread extends Thread {
        private final AtomicInteger i;
        private final List<Pair<AffineModel2D, double[][]>> triangles;
        private final PixelMapper pixelMapper;

        MapTriangleThread(final AtomicInteger i,
                          final List<Pair<AffineModel2D, double[][]>> triangles,
                          final PixelMapper pixelMapper) {
            this.i = i;
            this.triangles = triangles;
            this.pixelMapper = pixelMapper;
        }

        @Override
        final public void run() {
            int k = i.getAndIncrement();
            while (!isInterrupted() && k < triangles.size()) {
                mapTriangle(triangles.get(k), pixelMapper);
                k = i.getAndIncrement();
            }
        }
    }

    private static void mapTriangle(final Pair<AffineModel2D, double[][]> ai,
                                    final PixelMapper pixelMapper) {

        final int w = pixelMapper.getTargetWidth() - 1;
        final int h = pixelMapper.getTargetHeight() - 1;

        final double[][] pq = ai.b;

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(w, Util.roundPos(max[0]));
        final int maxY = Math.min(h, Util.roundPos(max[1]));

        final double[] source = new double[2];

        if (pixelMapper.isMappingInterpolated()) {

            for (int targetY = minY; targetY <= maxY; ++targetY) {
                for (int targetX = minX; targetX <= maxX; ++targetX) {

                    if (RenderTransformMesh.isInTargetTriangle(pq, targetX, targetY)) {

                        source[0] = targetX;
                        source[1] = targetY;

                        try {
                            ai.a.applyInverseInPlace(source);
                        } catch (final Exception e) {
                            LOG.warn("ignoring exception", e);
                            continue;
                        }

                        pixelMapper.mapInterpolated(source[0], source[1], targetX, targetY);
                    }
                }
            }

        } else {

            for (int targetY = minY; targetY <= maxY; ++targetY) {
                for (int targetX = minX; targetX <= maxX; ++targetX) {

                    if (RenderTransformMesh.isInTargetTriangle(pq, targetX, targetY)) {

                        source[0] = targetX;
                        source[1] = targetY;

                        try {
                            ai.a.applyInverseInPlace(source);
                        } catch (final Exception e) {
                            LOG.warn("ignoring exception", e);
                            continue;
                        }

                        pixelMapper.map(source[0], source[1], targetX, targetY);
                    }
                }
            }

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTransformMeshMappingWithMasks.class);
}
