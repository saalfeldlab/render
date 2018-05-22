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

import java.io.File;
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

        final AffineWarpFieldTransform transform =
                new AffineWarpFieldTransform(AffineWarpFieldTransform.EMPTY_OFFSETS,
                                             affineWarpField);

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

    @Test
    public void testOffsetWarpField() throws Exception {

        final AffineWarpFieldTransform affineWarpFieldTransform = new AffineWarpFieldTransform();

        final String valuesString = "1.0 1.0 1.0 1.0   0.0 0.0 0.0 0.0   0.0 0.0 0.0 0.0   1.0 1.0 1.0 1.0   11.0 0.0 0.0 0.0   22.0 0.0 0.0 0.0";
        affineWarpFieldTransform.init("50000.0 60000.0 100.0 100.0 2 2 net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory none " + valuesString);

        double[] world = { 50075, 60075 };

        double[] result = affineWarpFieldTransform.apply(world);
        Assert.assertEquals("invalid x result for row 1 column 1", world[0], result[0], 0.001);
        Assert.assertEquals("invalid y result for row 1 column 1", world[1], result[1], 0.001);

        world = new double[] { 5500, 6600 };

        result = affineWarpFieldTransform.apply(world);
        Assert.assertEquals("invalid x result for row 0 column 0", world[0] + 11, result[0], 0.001);
        Assert.assertEquals("invalid y result for row 0 column 0", world[1] + 22, result[1], 0.001);
    }

    @Test
    public void testApplyWithOffset() throws Exception {

        // after warped alignment, these coordinates should map to the same location
        // ( see http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold_rough_tiles_01_AGGREGATED_CONSENSUS_SETS_tier_1/stack/0003x0003_000002 )
        final double[] rough2213 = { 46214.0, 39961.0 };
        final double[] rough2214 = { 46068.0, 40484.0 };

        final AffineWarpFieldTransform warpTransform2213 = new AffineWarpFieldTransform();
        warpTransform2213.init("36801.0 38528.0 11193.0 10962.0 3 3 net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory none 0.9955773305800001 0.991077036436 0.993045280237 0.995354187201 1.000440714444 0.993790147206 0.996826382514 0.995984897583 0.993719307666 0.01409965501 0.016043896631 0.014746860293 -0.002873982878 -0.00116022483 0.013510382867 -0.001192439754 5.859217E-6 0.003145943468 -0.011013582015 -0.012338129925 -0.011908982997 9.48924979E-4 0.001618271534 -0.010230964811 -0.003459918858 -0.001934553341 -0.003394926989 1.005886392262 0.997166313186 0.999118607908 0.997128656592 0.99959916781 0.9938648030169999 0.995198137272 0.99522146313 0.996689024213 595.9319718807892 840.5108973976676 756.6997755585908 134.61896849736513 -90.68584564211778 653.6872400376087 276.2241293772531 252.27854943129933 442.0244489518518 -712.0535594469839 -444.47068907479843 -472.35482359516755 232.63408066138072 54.873101921861235 -171.56798092255485 266.64185113671556 213.42933412336424 9.520116822772252");
        System.out.println("\nwarpTransform2213: " + warpTransform2213.toDebugJson());

        final AffineWarpFieldTransform warpTransform2214 = new AffineWarpFieldTransform();
        warpTransform2214.init("36801.0 38528.0 11193.0 10962.0 3 3 net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory none 1.005916360785 0.999726895974 1.0032903825 0.999011164133 0.995630647938 0.999171576137 0.993627298016 0.992966230905 0.995784242226 -0.033505270333 -0.025013174227 -0.023106681427 0.010199359671 0.007466438547 -0.018462225246 0.004145377744 0.002953109544 -0.004881533511 0.02749785704 0.022379883781 0.020515981125000003 -0.004649267250000001 -0.007437328707999999 0.023141381705999997 0.001742504742 0.001594180262 0.003233441931 0.987061846555 0.9888275814660001 0.987195727255 0.9940432578330001 0.99379727775 0.987054589311 0.9988320354900001 0.990503841195 0.988658379966 -1285.8920960785908 -818.2145529960326 -876.2297317394696 243.17938852629595 517.9499065289638 -800.95992288657 161.5480024563949 209.21105711330165 30.77146844597155 1683.4249266760016 1254.852962180652 1233.1454154581006 -149.63373950312962 -24.064125550125027 1046.9034649959576 -117.76655505561939 323.5501992759964 755.2238918685835");
        System.out.println("\nwarpTransform2214: " + warpTransform2214.toDebugJson());

        final double[] warp2213 = warpTransform2213.apply(rough2213);
        final double[] warp2214 = warpTransform2214.apply(rough2214);

        Assert.assertEquals("warped X values are not aligned", warp2213[0], warp2214[0], 0.5);
        Assert.assertEquals("warped Y values are not aligned", warp2213[1], warp2214[1], 0.5);

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
                "--tile_spec_url src/test/resources/warp-field-test/montage_warp_tiles_rotate.json " + // montage_no_warp.json
                "--out test.jpg --x 0 --y 0 --width 1020 --height 1020 --scale 1.0";

        if (args.length < 14) {
            System.out.println("Example: " + exampleArgs);
        } else {
            for (int i = 1; i < args.length; i += 2) {
                if ("--out".equals(args[i - 1])) {
                    final File outFile = new File(args[i]);
                    if (outFile.isDirectory()) {
                        final File testFile = new File(outFile, "test_" + System.currentTimeMillis() + ".jpg");
                        args[i] = testFile.getAbsolutePath();
                    }
                }
            }
            ArgbRenderer.renderUsingCommandLineArguments(args);
        }

    }

}
