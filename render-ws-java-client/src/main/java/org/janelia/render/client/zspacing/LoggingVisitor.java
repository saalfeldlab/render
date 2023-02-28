package org.janelia.render.client.zspacing;

import org.janelia.thickness.inference.visitor.Visitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;

/**
 * Thickness correction {@link Visitor} that simply logs it has been called (to help track progress).
 *
 * @author Eric Trautman
 */
public class LoggingVisitor implements Visitor {
    @Override
    public <T extends RealType<T>> void act(final int iteration,
                                            final RandomAccessibleInterval<T> matrix,
                                            final RandomAccessibleInterval<T> scaledMatrix,
                                            final double[] lut,
                                            final int[] permutation,
                                            final int[] inversePermutation,
                                            final double[] multipliers,
                                            final RandomAccessibleInterval<double[]> estimatedFit) {
        LOG.info("act: entry, iteration={}", iteration);
    }

    private static final Logger LOG = LoggerFactory.getLogger(LoggingVisitor.class);
}
