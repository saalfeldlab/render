package org.janelia.alignment.betterbox;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link BoxDataPyramidForLayer} class.
 *
 * @author Eric Trautman
 */
public class BoxDataPyramidForLayerTest {

    @Test
    public void testBuild() throws Exception {

        // 5 levels:
        //   level 0 (a) => 16x16, first tile => row: 2, column: 5, last tile => row: 9, column: 12
        //   level 1 (b) => 8x8,   first tile => row: 1, column: 2, last tile => row: 4, column: 6
        //   level 2 (c) => 4x4,   first tile => row: 0, column: 1, last tile => row: 2, column: 3
        //   level 3 (d) => 2x2,   first tile => row: 0, column: 0, last tile => row: 1, column: 1
        //   level 4 (e) => 1x1,   first tile => row: 0, column: 0, last tile => row: 0, column: 0
        //
        // 10x10 boxes => minX: 20, minY: 120, maxX: 100, maxY: 200
        //
        //                                 1 1 1 1 1 1 1
        //               1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6
        //             0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
        //
        //         0   a b c c d d d d e e e e e e e e
        //        10   b b   c       d               e
        //        20   c     c   x x x x x x x x     e
        //        30   c c c c   x   d         x     e
        //        40   d         x   d         x     e
        //        50   d         x   d         x     e
        //        60   d         x   d         x     e
        //        70   d d d d d x d d         x     e
        //        80   e         x             x     e
        //        90   e         x x x x x x x x     e
        //       100   e                             e
        //       110   e                             e
        //       120   e                             e
        //       130   e                             e
        //       140   e                             e
        //       150   e e e e e e e e e e e e e e e e
        //       160
        //
        // 6x6 tiles: minX: 52, minY: 22, maxX: 52+(12*6)=124, maxY: 22+(12x6)=94

        final int tileSize = 6;
        final Double z = 99.1;
        final String sectionId = z.toString();
        final List<TileBounds> tileBoundsList = new ArrayList<>();

        final Bounds layerBounds = new Bounds(52.0, 22.0, 124.0, 94.0);
        for (int y = layerBounds.getMinY().intValue(); y < layerBounds.getMaxY(); y += tileSize) {
            for (int x = layerBounds.getMinX().intValue(); x < layerBounds.getMaxX(); x += tileSize) {
                tileBoundsList.add(new TileBounds("tile_" + tileBoundsList.size(),
                                                  sectionId, z,
                                                  (double) x, (double) y,
                                                  (double) x + tileSize, (double) y + tileSize));
            }
        }

        Assert.assertEquals("12x12 tile grid should have been created", 144, tileBoundsList.size());

        final int boxSize = 10;
        final BoxDataPyramidForLayer
                boxPyramid = new BoxDataPyramidForLayer(z, layerBounds, boxSize, boxSize, tileBoundsList, 4,
                                                        false, null, null);

        final List<BoxData> pyramidList = boxPyramid.getPyramidList();
        Assert.assertNotNull("missing pyramid", pyramidList);
        Assert.assertEquals("invalid number of boxes in pyramid", EXPECTED_LIST.length, pyramidList.size());

        String expectedLevelPath;
        String expectedServicePath;

        for (int i = 0; i < EXPECTED_LIST.length; i++) {
            expectedLevelPath = EXPECTED_LIST[i][0];
            expectedServicePath = EXPECTED_LIST[i][1];
            final BoxData boxData = pyramidList.get(i);
            Assert.assertEquals("invalid level path for box " + i,
                                expectedLevelPath, boxData.getLevelPath());
            Assert.assertEquals("invalid service path for box " + i,
                                expectedServicePath, boxData.getServicePath(boxSize, boxSize));

            if (boxData.getLevel() == 0) {
                Assert.assertFalse("level zero box " + i + " should not have children",
                                   boxData.hasChildren());
            } else {
                Assert.assertTrue("level N box " + i + " should have children",
                                   boxData.hasChildren());
            }
        }
    }

    private static final String[][] EXPECTED_LIST = {
            { "/0/99/2/5",  "/z/99.1/box/50,20,10,10,1.0" },    // box 0
            { "/0/99/3/5",  "/z/99.1/box/50,30,10,10,1.0" },
            { "/0/99/2/6",  "/z/99.1/box/60,20,10,10,1.0" },
            { "/0/99/2/7",  "/z/99.1/box/70,20,10,10,1.0" },
            { "/0/99/3/6",  "/z/99.1/box/60,30,10,10,1.0" },
            { "/0/99/3/7",  "/z/99.1/box/70,30,10,10,1.0" },    // box 5
            { "/0/99/2/8",  "/z/99.1/box/80,20,10,10,1.0" },
            { "/0/99/2/9",  "/z/99.1/box/90,20,10,10,1.0" },
            { "/0/99/3/8",  "/z/99.1/box/80,30,10,10,1.0" },
            { "/0/99/3/9",  "/z/99.1/box/90,30,10,10,1.0" },
            { "/0/99/2/10", "/z/99.1/box/100,20,10,10,1.0" },   // box 10
            { "/0/99/2/11", "/z/99.1/box/110,20,10,10,1.0" },
            { "/0/99/3/10", "/z/99.1/box/100,30,10,10,1.0" },
            { "/0/99/3/11", "/z/99.1/box/110,30,10,10,1.0" },
            { "/0/99/2/12", "/z/99.1/box/120,20,10,10,1.0" },
            { "/0/99/3/12", "/z/99.1/box/120,30,10,10,1.0" },   // box 15
            { "/0/99/4/5",  "/z/99.1/box/50,40,10,10,1.0" },
            { "/0/99/5/5",  "/z/99.1/box/50,50,10,10,1.0" },
            { "/0/99/4/6",  "/z/99.1/box/60,40,10,10,1.0" },
            { "/0/99/4/7",  "/z/99.1/box/70,40,10,10,1.0" },
            { "/0/99/5/6",  "/z/99.1/box/60,50,10,10,1.0" },    // box 20
            { "/0/99/5/7",  "/z/99.1/box/70,50,10,10,1.0" },
            { "/0/99/4/8",  "/z/99.1/box/80,40,10,10,1.0" },
            { "/0/99/4/9",  "/z/99.1/box/90,40,10,10,1.0" },
            { "/0/99/5/8",  "/z/99.1/box/80,50,10,10,1.0" },
            { "/0/99/5/9",  "/z/99.1/box/90,50,10,10,1.0" },    // box 25
            { "/0/99/4/10", "/z/99.1/box/100,40,10,10,1.0" },
            { "/0/99/4/11", "/z/99.1/box/110,40,10,10,1.0" },
            { "/0/99/5/10", "/z/99.1/box/100,50,10,10,1.0" },
            { "/0/99/5/11", "/z/99.1/box/110,50,10,10,1.0" },
            { "/0/99/4/12", "/z/99.1/box/120,40,10,10,1.0" },   // box 30
            { "/0/99/5/12", "/z/99.1/box/120,50,10,10,1.0" },
            { "/0/99/6/5",  "/z/99.1/box/50,60,10,10,1.0" },
            { "/0/99/7/5",  "/z/99.1/box/50,70,10,10,1.0" },
            { "/0/99/6/6",  "/z/99.1/box/60,60,10,10,1.0" },
            { "/0/99/6/7",  "/z/99.1/box/70,60,10,10,1.0" },    // box 35
            { "/0/99/7/6",  "/z/99.1/box/60,70,10,10,1.0" },
            { "/0/99/7/7",  "/z/99.1/box/70,70,10,10,1.0" },
            { "/0/99/6/8",  "/z/99.1/box/80,60,10,10,1.0" },
            { "/0/99/6/9",  "/z/99.1/box/90,60,10,10,1.0" },
            { "/0/99/7/8",  "/z/99.1/box/80,70,10,10,1.0" },    // box 40
            { "/0/99/7/9",  "/z/99.1/box/90,70,10,10,1.0" },
            { "/0/99/6/10", "/z/99.1/box/100,60,10,10,1.0" },
            { "/0/99/6/11", "/z/99.1/box/110,60,10,10,1.0" },
            { "/0/99/7/10", "/z/99.1/box/100,70,10,10,1.0" },
            { "/0/99/7/11", "/z/99.1/box/110,70,10,10,1.0" },   // box 45
            { "/0/99/6/12", "/z/99.1/box/120,60,10,10,1.0" },
            { "/0/99/7/12", "/z/99.1/box/120,70,10,10,1.0" },
            { "/0/99/8/5",  "/z/99.1/box/50,80,10,10,1.0" },
            { "/0/99/9/5",  "/z/99.1/box/50,90,10,10,1.0" },
            { "/0/99/8/6",  "/z/99.1/box/60,80,10,10,1.0" },    // box 50
            { "/0/99/8/7",  "/z/99.1/box/70,80,10,10,1.0" },
            { "/0/99/9/6",  "/z/99.1/box/60,90,10,10,1.0" },
            { "/0/99/9/7",  "/z/99.1/box/70,90,10,10,1.0" },
            { "/0/99/8/8",  "/z/99.1/box/80,80,10,10,1.0" },
            { "/0/99/8/9",  "/z/99.1/box/90,80,10,10,1.0" },    // box 55
            { "/0/99/9/8",  "/z/99.1/box/80,90,10,10,1.0" },
            { "/0/99/9/9",  "/z/99.1/box/90,90,10,10,1.0" },
            { "/0/99/8/10", "/z/99.1/box/100,80,10,10,1.0" },
            { "/0/99/8/11", "/z/99.1/box/110,80,10,10,1.0" },
            { "/0/99/9/10", "/z/99.1/box/100,90,10,10,1.0" },   // box 60
            { "/0/99/9/11", "/z/99.1/box/110,90,10,10,1.0" },
            { "/0/99/8/12", "/z/99.1/box/120,80,10,10,1.0" },
            { "/0/99/9/12", "/z/99.1/box/120,90,10,10,1.0" },
            { "/1/99/1/2",  "/z/99.1/box/40,20,20,20,0.5" },
            { "/1/99/1/3",  "/z/99.1/box/60,20,20,20,0.5" },    // box 65
            { "/1/99/1/4",  "/z/99.1/box/80,20,20,20,0.5" },
            { "/1/99/1/5",  "/z/99.1/box/100,20,20,20,0.5" },
            { "/1/99/1/6",  "/z/99.1/box/120,20,20,20,0.5" },
            { "/1/99/2/2",  "/z/99.1/box/40,40,20,20,0.5" },
            { "/1/99/2/3",  "/z/99.1/box/60,40,20,20,0.5" },    // box 70
            { "/1/99/3/2",  "/z/99.1/box/40,60,20,20,0.5" },
            { "/1/99/3/3",  "/z/99.1/box/60,60,20,20,0.5" },
            { "/1/99/2/4",  "/z/99.1/box/80,40,20,20,0.5" },
            { "/1/99/2/5",  "/z/99.1/box/100,40,20,20,0.5" },
            { "/1/99/3/4",  "/z/99.1/box/80,60,20,20,0.5" },    // box 75
            { "/1/99/3/5",  "/z/99.1/box/100,60,20,20,0.5" },
            { "/1/99/2/6",  "/z/99.1/box/120,40,20,20,0.5" },
            { "/1/99/3/6",  "/z/99.1/box/120,60,20,20,0.5" },
            { "/1/99/4/2",  "/z/99.1/box/40,80,20,20,0.5" },
            { "/1/99/4/3",  "/z/99.1/box/60,80,20,20,0.5" },    // box 80
            { "/1/99/4/4",  "/z/99.1/box/80,80,20,20,0.5" },
            { "/1/99/4/5",  "/z/99.1/box/100,80,20,20,0.5" },
            { "/1/99/4/6",  "/z/99.1/box/120,80,20,20,0.5" },
            { "/2/99/0/1",  "/z/99.1/box/40,0,40,40,0.25" },
            { "/2/99/1/1",  "/z/99.1/box/40,40,40,40,0.25" },   // box 85
            { "/2/99/0/2",  "/z/99.1/box/80,0,40,40,0.25" },
            { "/2/99/0/3",  "/z/99.1/box/120,0,40,40,0.25" },
            { "/2/99/1/2",  "/z/99.1/box/80,40,40,40,0.25" },
            { "/2/99/1/3",  "/z/99.1/box/120,40,40,40,0.25" },
            { "/2/99/2/1",  "/z/99.1/box/40,80,40,40,0.25" },   // box 90
            { "/2/99/2/2",  "/z/99.1/box/80,80,40,40,0.25" },
            { "/2/99/2/3",  "/z/99.1/box/120,80,40,40,0.25" },
            { "/3/99/0/0",  "/z/99.1/box/0,0,80,80,0.125" },
            { "/3/99/0/1",  "/z/99.1/box/80,0,80,80,0.125" },
            { "/3/99/1/0",  "/z/99.1/box/0,80,80,80,0.125" },
            { "/3/99/1/1",  "/z/99.1/box/80,80,80,80,0.125" },
            { "/4/99/0/0",  "/z/99.1/box/0,0,160,160,0.0625" }  // box 97
    };

}