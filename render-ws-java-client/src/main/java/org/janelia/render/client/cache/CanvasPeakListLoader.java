package org.janelia.render.client.cache;

import java.util.List;

import javax.annotation.Nonnull;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
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
     * @param  urlTemplate               template for deriving render parameters URL for each canvas.
     * @param  peakExtractor             configured peak extractor.
     */
    public CanvasPeakListLoader(final CanvasRenderParametersUrlTemplate urlTemplate,
                                final CanvasPeakExtractor peakExtractor) {
        super(urlTemplate, CachedCanvasPeaks.class);
        this.peakExtractor = peakExtractor;
    }

    @Override
    public CachedCanvasPeaks load(@Nonnull final CanvasId canvasId) {

        final RenderParameters renderParameters = getRenderParameters(canvasId);
        final double[] offsets = canvasId.getClipOffsets(); // HACK WARNING: offsets get applied by getRenderParameters call

        LOG.info("load: extracting peaks for {} with offsets ({}, {})", canvasId, offsets[0], offsets[1]);
        final List<DifferenceOfGaussianPeak<FloatType>> peakList = peakExtractor.extractPeaks(renderParameters, null);

        final List<DifferenceOfGaussianPeak<FloatType>> filteredPeakList =
                peakExtractor.nonMaximalSuppression(peakList, renderParameters.getScale());

        LOG.info("load: exit");

        return new CachedCanvasPeaks(filteredPeakList, offsets);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasPeakListLoader.class);
}
