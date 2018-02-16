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
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasFeatureMatcherTest;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;

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

            final List<Point> pointList = new ArrayList<>(pointMatchList.size());
            pointList.addAll(pointMatchList.stream().map(PointMatch::getP1).collect(Collectors.toList()));

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

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusWarpFieldBuilderTest.class);

}
