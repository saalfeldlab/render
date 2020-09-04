package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.PointRoi;
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

        final int sizeY = ccParameters.getScaledSampleSize(renderScale);
        final int stepY = ccParameters.getScaledStepSize(renderScale);

        LOG.debug("renderScale: {}, rThreshold: {}, sizeY: {}, stepY: {}",
                  renderScale, ccParameters.minResultThreshold, sizeY, stepY);

        final Rectangle r1 = findRectangle(mask1);
        final Rectangle r2 = findRectangle(mask2);

        final int startY = Math.min(r1.y, r2.y);
        final int endY = Math.max(r1.y + r1.height - 1, r2.y + r2.height - 1);

        final int numTests = (endY - startY - sizeY + stepY + 1) / stepY +
                             Math.min(1, (endY - startY - sizeY + stepY + 1) % stepY);
        final double incY = (endY - startY - sizeY + stepY + 1) / (double) numTests;

        LOG.debug(numTests + " " + incY);

        final List<PointMatch> candidates = new ArrayList<>();

        final PointRoi p1Candidates = new PointRoi();
        final PointRoi p2Candidates = new PointRoi();

        for (int i = 0; i < numTests; ++i) {
            final int minY = (int) Math.round(i * incY) + startY;
            final int maxY = minY + sizeY - 1;

            // LOG.debug( " " + minY  + " > " + maxY );

            final Rectangle r1PCM = new Rectangle(r1.x, minY, r1.width, maxY - minY + 1);
            final Rectangle r2PCM = new Rectangle(r2.x, minY, r2.width, maxY - minY + 1);

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

                LOG.debug(minY + " > " + maxY + ", shift : " + Util.printCoordinates(result.getOffset()) +
                          ", correlation (R)=" + result.getCrossCorrelation());

                double r1X = 0;
                final double r1Y = minY + sizeY / 2.0;

                double r2X = -result.getOffset(0);
                final double r2Y = minY + sizeY / 2.0 - result.getOffset(1);

                // just to place the points within the overlapping area
                // (only matters for visualization)
                double shiftX = 0;

                if (r2X < r2.x) {
                    shiftX += r2.x - r2X;
                } else if (r2X >= r2.x + r2.width) {
                    shiftX -= r2X - (r2.x + r2.width);
                }

                r1X += shiftX;
                r2X += shiftX;

                final Point p1 = new Point(new double[]{r1X, r1Y});
                final Point p2 = new Point(new double[]{r2X, r2Y});

                candidates.add(new PointMatch(p1, p2));

                p1Candidates.addPoint(r1X, r1Y);
                p2Candidates.addPoint(r2X, r2Y);
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
