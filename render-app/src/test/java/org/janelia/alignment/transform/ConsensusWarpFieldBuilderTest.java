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
package org.janelia.alignment.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasFeatureMatcherTest;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;
import net.imglib2.RealPoint;

/**
 * Tests the {@link ConsensusWarpFieldBuilder} class.
 *
 * @author Eric Trautman
 */
public class ConsensusWarpFieldBuilderTest {

    @Test
    public void testBuild() throws Exception {

        final List<List<PointMatch>> consensusSets = CanvasFeatureMatcherTest.findAndValidateFoldTestConsensusSets();

        final int consensusGridResolution = 16;
        final ConsensusWarpFieldBuilder consensusWarpFieldBuilder =
                new ConsensusWarpFieldBuilder(963.0, 1024.0, consensusGridResolution, consensusGridResolution);

        int setNumber = 0;
        for (final List<PointMatch> pointMatchList : consensusSets) {

            final List<RealPoint> pointList =
                    pointMatchList.stream()
                            .map(pointMatch -> new RealPoint(pointMatch.getP1().getL()))
                            .collect(Collectors.toList());

            final AffineModel2D alignmentModel = new AffineModel2D();
            alignmentModel.set(1.0, 0.0, 0.0, 1.0, (setNumber * 100), (setNumber * 100));
            consensusWarpFieldBuilder.addConsensusSetData(alignmentModel, pointList);
            setNumber++;
        }

        LOG.info("Consensus Grid is ...\n\n{}\n", consensusWarpFieldBuilder.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            3, consensusWarpFieldBuilder.getNumberOfConsensusSetsInGrid());


        final AffineWarpFieldTransform affineWarpFieldTransform =
                new AffineWarpFieldTransform(new double[] {0.0, 0.0},
                                             consensusWarpFieldBuilder.build());

        LOG.info("Warp Field Data String is {}", affineWarpFieldTransform.toDataString());
    }

    @Test
    public void testMerge() throws Exception {

        final int gridRes = 10;
        final double cellSize = 1000.0;
        final ConsensusWarpFieldBuilder builder1 = new ConsensusWarpFieldBuilder(cellSize, cellSize, gridRes, gridRes);
        final ConsensusWarpFieldBuilder builder2 = new ConsensusWarpFieldBuilder(cellSize, cellSize, gridRes, gridRes);

        final List<RealPoint> set1A = new ArrayList<>();
        final List<RealPoint> set1B = new ArrayList<>();
        final List<RealPoint> set1C = new ArrayList<>();

        int column = 0;
        for (int row = 0; row < 10; row++) {
            if (row < 5) {
                addPoint(column * 100, row * 100, set1A);
                addPoint((column + 1) * 100, row * 100, set1B);
                column++;
            } else {
                addPoint(column * 100, row * 100, set1C);
                addPoint((column - 1) * 100, row * 100, set1A);
                column--;
            }
        }

        for (int c = 6; c < 10; c++) {
            addPoint(c * 100, 400, set1B);
            addPoint(c * 100, 500, set1C);
        }

        builder1.addConsensusSetData(new AffineModel2D(), set1A);
        builder1.addConsensusSetData(new AffineModel2D(), set1B);
        builder1.addConsensusSetData(new AffineModel2D(), set1C);

        LOG.info("Grid 1 is ...\n\n{}\n", builder1.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            3, builder1.getNumberOfConsensusSetsInGrid());

        final List<RealPoint> set2A = new ArrayList<>();
        final List<RealPoint> set2B = new ArrayList<>();

        for (column = 0; column < 10; column++) {
            for (int row = 0; row < 10; row++) {
                if (column < 4) {
                    addPoint(column * 100, row * 100, set2A);
                } else {
                    addPoint(column * 100, row * 100, set2B);
                }
            }
        }

        builder2.addConsensusSetData(new AffineModel2D(), set2A);
        builder2.addConsensusSetData(new AffineModel2D(), set2B);

        LOG.info("Grid 2 is ...\n\n{}\n", builder2.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after nearest neighbor analysis",
                            2, builder2.getNumberOfConsensusSetsInGrid());

        final ConsensusWarpFieldBuilder mergedBuilder = builder1.mergeBuilders(builder2);

        LOG.info("Merged Grid is ...\n\n{}\n", mergedBuilder.toIndexGridString());

        Assert.assertEquals("invalid number of consensus sets left in grid after merge",
                            6, mergedBuilder.getNumberOfConsensusSetsInGrid());
    }

    private void addPoint(final int x,
                          final int y,
                          final List<RealPoint> pointList) {
        pointList.add(new RealPoint(x, y));
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusWarpFieldBuilderTest.class);

}
