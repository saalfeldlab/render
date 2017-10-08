package org.janelia.render.service;

import java.util.Collections;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TileDataService} class.
 *
 * @author Eric Trautman
 */
public class TileDataServiceTest {

    @Test
    public void testGetCoreTileRenderParameters() throws Exception {

        final String json =
                "{\n" +
                "  \"tileId\" : \"1,3484_aligned_0_1_flip\",\n" +
                "  \"z\" : 3484.0, \"minX\" : 1896.0, \"minY\" : 876.0, \"maxX\" : 2919.0, \"maxY\" : 1899.0,\n" +
                "  \"width\" : 1024.0, \"height\" : 1024.0,\n" +
                "  \"mipmapLevels\" : {\n" +
                "    \"0\" : {\n" +
                "      \"imageUrl\" : \"file:///data/nc-em/russelt/20170227_Princeton_Pinky40/4_aligned_tiled/1,3484_aligned_0_1_flip.png\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"transforms\" : {\n" +
                "    \"type\" : \"list\",\n" +
                "    \"specList\" : [ {\n" +
                "         \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "         \"dataString\" : \"1.0000000000 0.0000000000 0.0000000000 1.0000000000 1896.0000000000 -876.0000000000\"\n" +
                "      }, {\n" +
                "          \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "          \"dataString\" : \"1.0000000000 0.0000000000 0.0000000000 1.0000000000 0.0000000000 1752.0000000000\"\n" +
                "      } ]\n" +
                "  }\n" +
                "}";

        TileSpec tileSpec = TileSpec.fromJson(json);

        RenderParameters renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null,
                                                            null,
                                                            null, null, null,
                                                            tileSpec);

        Assert.assertEquals("invalid width for tile", 1024, renderParameters.getWidth());
        Assert.assertEquals("invalid height for tile", 1024, renderParameters.getHeight());

        // ---------------------------------------------------
        tileSpec = TileSpec.fromJson(json);

        renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null,
                                                            true,
                                                            null, null, null,
                                                            tileSpec);

        List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        Assert.assertEquals("invalid number of tile specs returned after normalization", 1, tileSpecs.size());

        TileSpec flattenedTileSpec = tileSpecs.get(0);
        ListTransformSpec transforms = flattenedTileSpec.getTransforms();
        Assert.assertEquals("invalid number of transforms after normalization", 1, transforms.size());

        // ---------------------------------------------------
        tileSpec = TileSpec.fromJson(json);

        renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null,
                                                            true,
                                                            Collections.emptySet(), Collections.emptySet(), null,
                                                            tileSpec);

        tileSpecs = renderParameters.getTileSpecs();
        Assert.assertEquals("invalid number of tile specs returned with empty sets", 1, tileSpecs.size());

        flattenedTileSpec = tileSpecs.get(0);
        transforms = flattenedTileSpec.getTransforms();
        Assert.assertEquals("invalid number of transforms with empty sets", 1, transforms.size());

    }

}
