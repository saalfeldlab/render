package org.janelia.alignment.warp;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import mpicbg.trakem2.transform.ThinPlateSplineTransform;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link ThinPlateSplineBuilder} class.
 *
 * @author Eric Trautman
 */
public class ThinPlateSplineBuilderTest {

    @Test
    public void testPilotSection() throws Exception {
        final List<TileSpec> montageTiles = getTiles("20141216_863_montage.2119.json");
        final List<TileSpec> alignTiles = getTiles("20141216_863_align.2119.json");
//        final List<TileSpec> montageTiles = getTiles("20150124_montage.5455.json");
//        final List<TileSpec> alignTiles = getTiles("20150124_align.5455.json");
        final ThinPlateSplineBuilder builder = new ThinPlateSplineBuilder(montageTiles,
                                                                          alignTiles);
        final ThinPlateSplineTransform transform = builder.call();

        final TransformSpec tpsSpec = new LeafTransformSpec("test_tps",
                                                            null,
                                                            transform.getClass().getName(),
                                                            transform.toDataString());

        TileSpec montageTileSpec;
        TileSpec alignTileSpec;
        double[] montageCenter;
        double[] alignCenter;

        final double acceptableCenterDelta = 0.001;


        final HashMap<String, TileSpec> alignTileSpecsLUT = new HashMap<>();
        for (final TileSpec ts : alignTiles) {
            alignTileSpecsLUT.put(ts.getTileId(), ts);
        }

        LOG.info("montage.size = {}; align.size = {}", montageTiles.size(), alignTiles.size());

        int j = 0;
        for (final TileSpec montageTile : montageTiles) {

            montageTileSpec = montageTile;
            montageTileSpec.addTransformSpecs(Collections.singletonList(tpsSpec));

            montageCenter = getCenter(montageTileSpec);

            alignTileSpec = alignTileSpecsLUT.get(montageTileSpec.getTileId());

            if (alignTileSpec == null) {
                continue;
            }

            ++j;

            alignCenter = getCenter(alignTileSpec);

            Assert.assertEquals(montageCenter[0], alignCenter[0], acceptableCenterDelta);
            Assert.assertEquals(montageCenter[1], alignCenter[1], acceptableCenterDelta);
        }

        LOG.info("intersection.size = {}", j);
    }

    private List<TileSpec> getTiles(final String jsonFileName) throws IOException {
        final File jsonFile = new File("src/test/resources/warp-test/" + jsonFileName);
        final List<TileSpec> tileSpecs;
        try (Reader reader = new FileReader(jsonFile)) {
            tileSpecs = TileSpec.fromJsonArray(reader);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(
                    "failed to parse tile specifications loaded from " + jsonFile.getAbsolutePath(), t);
        }
        return tileSpecs;
    }

    private double[] getCenter(final TileSpec tileSpec) {
        final double[] center = new double[] {
                tileSpec.getWidth() * 0.5,
                tileSpec.getHeight() * 0.5
        };
        tileSpec.getTransforms().getNewInstance().applyInPlace(center);
        return center;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ThinPlateSplineBuilderTest.class);

}
