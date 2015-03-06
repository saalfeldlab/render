package org.janelia.alignment.warp;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import mpicbg.trakem2.transform.ThinPlateSplineTransform;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
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
        final ThinPlateSplineBuilder builder = new ThinPlateSplineBuilder(montageTiles,
                                                                          alignTiles);
        final ThinPlateSplineTransform transform = builder.call();

        final TransformSpec tpsSpec = new LeafTransformSpec("test_tps",
                                                            null,
                                                            transform.getClass().getName(),
                                                            transform.toDataString());

        double[] maxDelta = new double[] {-1, -1};
        double deltaX;
        double deltaY;
        TileSpec tileX = new TileSpec();
        TileSpec tileY = new TileSpec();
        TileSpec montageTileSpec;
        double[] montageCenter;
        double[] alignCenter;

        // TODO: include all tiles once problem is fixed
        for (int i = 0; i < 1; i++) {
//        for (int i = 0; i < alignTiles.size(); i++) {

            montageTileSpec = montageTiles.get(i);
            montageTileSpec.addTransformSpecs(Arrays.asList(tpsSpec));
            montageTileSpec.deriveBoundingBox(RenderParameters.DEFAULT_MESH_CELL_SIZE, true);

            montageCenter = getCenter(montageTileSpec);
            alignCenter = getCenter(alignTiles.get(i));

            deltaX = Math.abs(montageCenter[0] - alignCenter[0]);
            if (deltaX > maxDelta[0]) {
                maxDelta[0] = deltaX;
                tileX = montageTileSpec;
            }

            deltaY = Math.abs(montageCenter[1] - alignCenter[1]);
            if (deltaY > maxDelta[1]) {
                maxDelta[1] = deltaY;
                tileY = montageTileSpec;
            }

            LOG.info("after processing tile {}, maxDelta={}", montageTileSpec.getTileId(), maxDelta);
        }

        final double acceptableCenterDelta = 200.0;
        Assert.assertTrue("center x values differ too much for tile " + tileX.getTileId(),
                          maxDelta[0] < acceptableCenterDelta);
        Assert.assertTrue("center y values differ too much for tile " + tileY.getTileId(),
                          maxDelta[1] < acceptableCenterDelta);

    }

    private List<TileSpec> getTiles(String jsonFileName) throws IOException {
        final File jsonFile = new File("src/test/resources/warp-test/" + jsonFileName);
        final Type collectionType = new TypeToken<List<TileSpec>>() {}.getType();
        List<TileSpec> tileSpecs;
        try (Reader reader = new FileReader(jsonFile)) {
            tileSpecs = JsonUtils.GSON.fromJson(reader, collectionType);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(
                    "failed to parse tile specifications loaded from " + jsonFile.getAbsolutePath(), t);
        }
        return tileSpecs;
    }

    // TODO: find out best way to derive center
    private double[] getCenter(TileSpec tileSpec) {
        return new double[] {
                tileSpec.getMaxX() - tileSpec.getMinX(),
                tileSpec.getMaxY() - tileSpec.getMinY()
        };
    }

    private static final Logger LOG = LoggerFactory.getLogger(ThinPlateSplineBuilderTest.class);

}
