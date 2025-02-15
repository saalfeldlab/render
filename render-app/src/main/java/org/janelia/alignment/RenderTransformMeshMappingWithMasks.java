/*
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
import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.util.Pair;
import mpicbg.util.Util;

import net.imglib2.realtransform.AffineTransform2D;
import org.janelia.alignment.Triangle.Range;
import org.janelia.alignment.mapper.PixelMapper;
import org.janelia.alignment.mapper.PixelMapper.LineMapper;
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
            Exception lastIgnoredException = null;
            for (final Pair<AffineModel2D, double[][]> triangle : av) {
                lastIgnoredException = mapTriangle(triangle, pixelMapper);
            }
            if (lastIgnoredException != null) {
                LOG.warn("map: ignored exception(s), this is the last one ignored", lastIgnoredException);
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
            Exception lastIgnoredException = null;
            while (!isInterrupted() && k < triangles.size()) {
                lastIgnoredException = mapTriangle(triangles.get(k), pixelMapper);
                k = i.getAndIncrement();
            }
            if (lastIgnoredException != null) {
                LOG.warn("map: ignored exception(s), this is the last one ignored", lastIgnoredException);
            }
        }
    }

    /**
     * Extract the inverse {@code model} transform
     * (mapping target to source coordinates).
     *
     * @param model
     * @return the inverse model transform
     * @throws NoninvertibleModelException
     */
    private static AffineTransform2D getTransformToSource(AffineModel2D model) throws NoninvertibleModelException {
        try {
            final AffineTransform2D transform = new AffineTransform2D();
            final double[] m = new double[6];
            model.toArray(m);
            transform.set(m[0], m[2], m[4], m[1], m[3], m[5]);
            return transform.inverse();
        } catch (final Exception e) {
            throw new NoninvertibleModelException("Model not invertible.");
        }
    }


    private static Exception mapTriangle(final Pair<AffineModel2D, double[][]> ai,
                                         final PixelMapper pixelMapper) {

        final double[][] pq = ai.b;
        final AffineModel2D model = ai.a;

        final Triangle triangle = new Triangle(pq[2][0], pq[3][0], pq[2][1], pq[3][1], pq[2][2], pq[3][2]);

        final double[] min = new double[2];
        final double[] max = new double[2];
        RenderTransformMesh.calculateTargetBoundingBox(pq, min, max);

        final int minX = Math.max(0, Util.roundPos(min[0]));
        final int minY = Math.max(0, Util.roundPos(min[1]));
        final int maxX = Math.min(pixelMapper.getTargetWidth() - 1, Util.roundPos(max[0]));
        final int maxY = Math.min(pixelMapper.getTargetHeight() - 1, Util.roundPos(max[1]));

        final AffineTransform2D affineTransform2D;
            try {
            affineTransform2D = getTransformToSource(model);
        } catch (final NoninvertibleModelException e) {
            return e;
        }

        final LineMapper lineMapper = pixelMapper.createLineMapper();
        final double dx = affineTransform2D.d(0).getDoublePosition(0);
        final double dy = affineTransform2D.d(0).getDoublePosition(1);
        final double[] source = new double[2];
        for (int targetY = minY; targetY <= maxY; ++targetY) {
            final Range xRange = triangle.intersect(targetY, minX, maxX + 1);
            source[0] = xRange.from();
            source[1] = targetY;
            affineTransform2D.apply(source, source);
            lineMapper.map(source[0], source[1], dx, dy, xRange.from(), targetY, xRange.length());
        }

        return null;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTransformMeshMappingWithMasks.class);
}
