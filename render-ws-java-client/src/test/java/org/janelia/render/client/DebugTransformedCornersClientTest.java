package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link DebugTransformedCornersClient} class.
 *
 * @author Eric Trautman
 */
public class DebugTransformedCornersClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new DebugTransformedCornersClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",

                // include multiple stacks to compare
//                "--stack", "v1_acquire_slab_001",
                "--stack", "v1_acquire_slab_001_trimmed_align",

                "--xyNeighborFactor", "0.6", // for tiles completely within mFov
//                "--xyNeighborFactor", "0.3", // for tiles that overlap another mFov

                "--zNeighborDistance", "0",
//                "--zNeighborDistance", "1",

//                "--tileId", "001_000004_004_20220401_172116.1225.0", // very similar stage and align results
//                "--tileId", "001_000003_070_20220401_172114.1225.0", // slight differences between stage and align

                //"--tileIdPattern", "001_000003_070.*",
                //"--tileId2", "001_000003_045",
                //[1.0, 0.0, 957.4067568339881], [0.0, 1.0, -1640.2727272727273]
                //
                //maxE=1.6049345812316815 [3,3](AffineTransform[[1.000073768550047, -2.29339951032E-4, 958.9466570005668], [1.62695801648E-4, 0.999838609012262, -1639.1777654103007]]) 0.5625111120982573, AffineModel2D
                //maxE=3.075175615242273 [3,3](AffineTransform[[1.0, 0.0, 957.4067568339881], [0.0, 1.0, -1640.2727272727273]]) 1.8803012773591163, AffineModel2D

                //"--tileIdPattern", "001_000004_005.*",
                //"--tileId2", "001_000004_006",
                // [1.0, 0.0, 946.3353229867118], [-0.0, 1.0, 1650.4545454545455]
                //
                //maxE=1.9563283848992057 [3,3](AffineTransform[[1.000004430187868, -7.6385492251E-5, 947.0522512333544], [-2.214604778E-5, 1.000051525513153, 1650.523026926238]]) 0.6812331662453539, AffineModel2D
                //maxE=2.3338589474988547 [3,3](AffineTransform[[1.0, -0.0, 946.3353229867118], [0.0, 1.0, 1650.4545454545455]]) 0.9487593150079552, AffineModel2D

                "--tileIdPattern", "001_000005_072.*",
                "--tileId2", "001_000005_046",
                
                //"--tileIdPattern", "001_000001_067.*",
                //"--tileId2", "001_000003_077",
                // [0.999999999999999, 0.0, 1151.79207584183], [0.0, 1.0, 696.090909090909]
                //
                //maxE=272.56485273576925 [3,3](AffineTransform[[0.999931428569802, 5.54519366791E-4, 1262.277557183158], [2.44823932572E-4, 0.999317490619089, 58.192734638376805]]) 109.49038746605288, AffineModel2D
                //maxE=758.769723341546 [3,3](AffineTransform[[0.999999999999999, 0.0, 1151.79207584183], [0.0, 1.0, 696.090909090909]]) 654.2757351802012, AffineModel2D

                //"--tileIdPattern", "001_000004_070.*",
                //"--tileId2", "001_000003_083",
                // [1.0, 0.0, 932.35990682446], [0.0, 1.0, 177.27272727272725]
                //
                // maxE=49.192674559661086 [3,3](AffineTransform[[1.000061385898745, -1.98857928079E-4, 758.1621057279375], [4.65293171531E-4, 1.000343670695631, 177.08083799589784]]) 20.898018013086354, AffineModel2D
                // maxE=192.18467329118556 [3,3](AffineTransform[[1.0, 0.0, 932.35990682446], [0.0, 1.0, 177.27272727272725]]) 174.9943695602127, AffineModel2D


                "--maxZ", "1235",
        };

        DebugTransformedCornersClient.main(effectiveArgs);

    }
}
