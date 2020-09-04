package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.util.Timer;

import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Util;

import static mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Derives correlation match results for the specified canvases, filtering out outlier matches.
 *
 * @author Stephan Preibisch
 */
public class CanvasCorrelationMatcher
        implements Serializable {

    private final CrossCorrelationParameters ccParameters;
    private final MatchFilter matchFilter;
    private final double renderScale;

    /**
     * Sets up everything that is needed to derive cross correlation match results from two canvases.
     */
    public CanvasCorrelationMatcher(final CrossCorrelationParameters ccParameters,
                                    final MatchDerivationParameters matchParameters,
                                    final double renderScale) {
        this.ccParameters = ccParameters;
        this.matchFilter = new MatchFilter(matchParameters);
        this.renderScale = renderScale;
    }

    /**
     * Standard method for deriving results from rendered canvases.
     *
     * @return match results for the specified canvases.
     */
    public CanvasMatchResult deriveMatchResult(final ImageProcessorWithMasks canvas1,
                                               final ImageProcessorWithMasks canvas2) {
        final ImagePlus ip1 = new ImagePlus("canvas1", canvas1.ip);
        final ImagePlus ip2 = new ImagePlus("canvas2", canvas2.ip);
        return deriveMatchResult(ip1, canvas1.mask, ip2, canvas2.mask, false);
    }

    /**
     * Match derivation method that supports interactive visualization.
     *
     * @return match results for the specified canvases and masks.
     */
    public CanvasMatchResult deriveMatchResult(final ImagePlus canvas1,
                                               final ImageProcessor mask1,
                                               final ImagePlus canvas2,
                                               final ImageProcessor mask2,
                                               final boolean visualizeSampleRois) {

        LOG.info("deriveMatchResult: entry");

        final Timer timer = new Timer();
        timer.start();

        final List<PointMatch> candidates = getCandidateMatches(canvas1, mask1, canvas2, mask2, visualizeSampleRois);

        final CanvasMatchResult result = matchFilter.buildMatchResult(candidates);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    /**
     * @return list of candidate inlier matches for filtering.
     */
    private List<PointMatch> getCandidateMatches(final ImagePlus ip1,
                                                 final ImageProcessor mask1,
                                                 final ImagePlus ip2,
                                                 final ImageProcessor mask2,
                                                 final boolean visualizeSampleRois) {

        final int scaledSampleSize = ccParameters.getScaledSampleSize(renderScale);
        final int scaledStepSize = ccParameters.getScaledStepSize(renderScale);

        final Rectangle unmaskedArea1 = findRectangle(mask1);
        final Rectangle unmaskedArea2 = findRectangle(mask2);

        final boolean stepThroughY = mask1.getHeight() > mask1.getWidth();

        final int startStep;
        final int maxHeightOrWidth;
        if (stepThroughY) {
            startStep = Math.min(unmaskedArea1.y, unmaskedArea2.y);
            maxHeightOrWidth = Math.max(unmaskedArea1.y + unmaskedArea1.height - 1,
                                        unmaskedArea2.y + unmaskedArea2.height - 1);
        } else {
            startStep = Math.min(unmaskedArea1.x, unmaskedArea2.x);
            maxHeightOrWidth = Math.max(unmaskedArea1.x + unmaskedArea1.width - 1,
                                        unmaskedArea2.x + unmaskedArea2.width - 1);
        }
        final int endStep = maxHeightOrWidth - startStep - scaledSampleSize + scaledStepSize + 1;
        final int numTests = (endStep / scaledStepSize) + Math.min(1, (endStep % scaledStepSize));
        final double stepIncrement = endStep / (double) numTests;

        LOG.debug("getCandidateMatches: renderScale={}, minResultThreshold={}, scaledSampleSize={}, scaledStepSize={}, numTests={}, stepIncrement={}",
                  renderScale, ccParameters.minResultThreshold, scaledSampleSize, scaledStepSize, numTests, stepIncrement);

        final List<PointMatch> candidates = new ArrayList<>();

        for (int i = 0; i < numTests; ++i) {

            final int minXOrY = (int) Math.round(i * stepIncrement) + startStep;
            final int maxXOrY = minXOrY + scaledSampleSize - 1;
            final int sampleWidthOrHeight = maxXOrY - minXOrY + 1;

            final Rectangle r1PCM, r2PCM;
            if (stepThroughY) {
                r1PCM = new Rectangle(unmaskedArea1.x, minXOrY, unmaskedArea1.width, sampleWidthOrHeight);
                r2PCM = new Rectangle(unmaskedArea2.x, minXOrY, unmaskedArea2.width, sampleWidthOrHeight);
            } else {
                r1PCM = new Rectangle(minXOrY, unmaskedArea1.y, sampleWidthOrHeight, unmaskedArea1.height);
                r2PCM = new Rectangle(minXOrY, unmaskedArea2.y, sampleWidthOrHeight, unmaskedArea2.height);
            }

            final Roi roi1 = new Roi(r1PCM);
            final Roi roi2 = new Roi(r2PCM);

            if (visualizeSampleRois) {
                ip1.setRoi(roi1);
                ip2.setRoi(roi2);
            }

            final StitchingParameters params = ccParameters.toStitchingParameters();

            final PairWiseStitchingResult result =
                    PairWiseStitchingImgLib.stitchPairwise(ip1,
                                                           ip2,
                                                           roi1,
                                                           roi2,
                                                           1,
                                                           1,
                                                           params);

            if (result.getCrossCorrelation() >= ccParameters.minResultThreshold) {

                LOG.debug(minXOrY + " > " + maxXOrY + ", shift : " + Util.printCoordinates(result.getOffset()) +
                          ", correlation (R)=" + result.getCrossCorrelation());

                final int stepDim = stepThroughY ? 1 : 0;
                final int otherDim = stepThroughY ? 0 : 1;
                final double r1XOrY = 0;
                final double center1XorY = minXOrY + scaledSampleSize / 2.0;

                final double r2XOrY = -result.getOffset(otherDim);
                final double center2XorY = center1XorY - result.getOffset(stepDim);

                // just to place the points within the overlapping area
                // (only matters for visualization)
                double shiftXOrY = 0;

                final int unmasked2XOrY = stepThroughY ? unmaskedArea2.x : unmaskedArea2.y;
                final int unmasked2WidthOrHeight = stepThroughY ? unmaskedArea2.width : unmaskedArea2.height;
                if (r2XOrY < unmasked2XOrY) {
                    shiftXOrY += unmasked2XOrY - r2XOrY;
                } else if (r2XOrY >= unmasked2XOrY + unmasked2WidthOrHeight) {
                    shiftXOrY -= r2XOrY - (unmasked2XOrY + unmasked2WidthOrHeight);
                }

                final Point p1, p2;
                if (stepThroughY) {
                    p1 = new Point(new double[]{r1XOrY + shiftXOrY, center1XorY});
                    p2 = new Point(new double[]{r2XOrY + shiftXOrY, center2XorY});
                } else {
                    p1 = new Point(new double[]{center1XorY, r1XOrY + shiftXOrY});
                    p2 = new Point(new double[]{center2XorY, r2XOrY + shiftXOrY});
                }

                candidates.add(new PointMatch(p1, p2));
            }
        }

        return candidates;
    }

    private static Rectangle findRectangle(final ImageProcessor mask) {
        // TODO: assumes it is not rotated

        int minX = mask.getWidth();
        int maxX = 0;

        int minY = mask.getHeight();
        int maxY = 0;

        for (int y = 0; y < mask.getHeight(); ++y) {
            for (int x = 0; x < mask.getWidth(); ++x) {
                if (mask.getf(x, y) >= 255) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        LOG.debug("minX: {}, maxX: {}, minY: {}, maxY: {}", minX, maxX, minY, maxY);

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasCorrelationMatcher.class);
}
