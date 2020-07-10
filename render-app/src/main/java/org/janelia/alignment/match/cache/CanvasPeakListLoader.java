package org.janelia.alignment.match.cache;

import java.util.List;

import javax.annotation.Nonnull;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts peaks for a canvas and loads them into the cache.
 *
 * @author Eric Trautman
 */
public class CanvasPeakListLoader
        extends CanvasDataLoader {

    private final CanvasPeakExtractor peakExtractor;

    /**
     * @param  peakExtractor             configured peak extractor.
     */
    public CanvasPeakListLoader(final CanvasPeakExtractor peakExtractor) {
        super(CachedCanvasPeaks.class);
        this.peakExtractor = peakExtractor;
    }

    @Override
    public CachedCanvasPeaks load(@Nonnull final CanvasIdWithRenderContext canvasIdWithRenderContext) {

        final RenderParameters renderParameters = canvasIdWithRenderContext.loadRenderParameters();

        final List<DifferenceOfGaussianPeak<FloatType>> peakList = peakExtractor.extractPeaks(renderParameters,
                                                                                              null);

        final List<DifferenceOfGaussianPeak<FloatType>> filteredPeakList =
                peakExtractor.nonMaximalSuppression(peakList, renderParameters.getScale());

        LOG.info("load: exit");

        return new CachedCanvasPeaks(filteredPeakList, canvasIdWithRenderContext.getClipOffsets());
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasPeakListLoader.class);
}
