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

import java.util.Collections;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.mipmap.RenderedCanvasMipmapSource;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link Utils} class.
 *
 * @author Eric Trautman
 */
public class UtilsTest {

    @Test
    public void testSampleAverageScaleAndBestMipmapLevel() throws Exception {

        final int width = 2560;
        final int height = 2160;

        final TileSpec standardTileSpec = getAffineTileSpec("1.0  0.0  0.0   1.0  0.0     0.0");
        final TileSpec flippedTileSpec =  getAffineTileSpec("1.0  0.0  0.0  -1.0  0.0  2160.0");

        final double[][] testData = new double[][] {
                // scale     expected mipmap level
                {  1.0,       0.0                    },
                {  0.5,       1.0                    },
                {  0.4,       1.0                    },
                {  0.25,      2.0                    },
                {  0.125,     3.0                    },
                {  0.05,      4.0                    },
                {  0.0625,    4.0                    },
                {  0.0001,   13.0                    }
        };

        double scale;
        double expectedMipmapLevel;
        double mipmapLevel;
        for (final double[] test : testData) {

            scale = test[0];
            expectedMipmapLevel = test[1];

            mipmapLevel = deriveMipmapLevel(standardTileSpec, scale, width, height);

            Assert.assertEquals("incorrect mipmap level derived for standard transform with scale " + scale,
                                expectedMipmapLevel, mipmapLevel, 0.0);

            mipmapLevel = deriveMipmapLevel(flippedTileSpec, scale, width, height);

            Assert.assertEquals("incorrect mipmap level derived for flipped transform with scale " + scale,
                                expectedMipmapLevel, mipmapLevel, 0.0);
        }

    }

    private TileSpec getAffineTileSpec(final String affineData) {
        final TransformSpec transformSpec = new LeafTransformSpec(AffineModel2D.class.getName(), affineData);
        final TileSpec tileSpec = new TileSpec();
        tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
        return tileSpec;
    }

    private double deriveMipmapLevel(final TileSpec tileSpec,
                                     final double scale,
                                     final int width,
                                     final int height) {
        final double x = 0;
        final double y = 0;
        final double meshCellSize = 64.0;

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, scale);
        final double levelZeroScale = renderParameters.getRes(renderParameters.getScale());

        final CoordinateTransformList<CoordinateTransform> coordinateTransformList =
                RenderedCanvasMipmapSource.addRenderScaleAndOffset(tileSpec.getTransformList(),
                                                                   levelZeroScale,
                                                                   scale,
                                                                   x,
                                                                   y);

        final double sampleAverageScale =
                Utils.sampleAverageScale(coordinateTransformList, width, height, meshCellSize);

        return Utils.bestMipmapLevel(sampleAverageScale);
    }
}
