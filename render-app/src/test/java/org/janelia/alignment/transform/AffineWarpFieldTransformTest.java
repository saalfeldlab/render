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

import java.util.Collections;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link AffineWarpFieldTransform} class.
 *
 * @author Eric Trautman
 */
public class AffineWarpFieldTransformTest {

    @Test
    public void testPersistence() throws Exception {

        final AffineWarpField affineWarpField =
                new AffineWarpField(1000, 1000, 2, 2, AffineWarpField.getDefaultInterpolatorFactory());
        affineWarpField.set(0, 0, new double[] {1, 0, 0, 1,  0, 0});
        affineWarpField.set(0, 1, new double[] {1, 0, 0, 1,  0, 0});
        affineWarpField.set(1, 0, new double[] {1, 0, 0, 1,  0, 0});
        affineWarpField.set(1, 1, new double[] {1, 0, 0, 1, 29, 29});

        final AffineWarpFieldTransform transform = new AffineWarpFieldTransform(affineWarpField);

        final String dataString = transform.toDataString();

        final AffineWarpFieldTransform loadedTransform = new AffineWarpFieldTransform();

        loadedTransform.init(dataString);

        Assert.assertEquals("data strings do not match", dataString, loadedTransform.toDataString());

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setWidth(100.0);
        tileSpec.setHeight(100.0);
        final LeafTransformSpec transformSpec = new LeafTransformSpec(loadedTransform.getClass().getName(),
                                                                      dataString);
        tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        // System.out.println(tileSpec.toJson());
    }

    public static void main(final String[] args)
            throws Exception {

        // interpolator factory options:
        //   FloorInterpolatorFactory
        //   NearestNeighborInterpolatorFactory
        //   NLinearInterpolatorFactory

        // invalid factory types:
        //   LanczosInterpolatorFactory
        //   NLinearInterpolatorARGBFactory

        final String exampleArgs =
                "--tile_spec_url src/test/resources/warp-field-test/montage_warp.json " + // montage_no_warp.json
                "--out test.jpg --x 0 --y 0 --width 1020 --height 1020 --scale 1.0";

        if (args.length < 14) {
            System.out.println("Example: " + exampleArgs);
        } else {
            ArgbRenderer.renderUsingCommandLineArguments(args);
        }

    }

}
