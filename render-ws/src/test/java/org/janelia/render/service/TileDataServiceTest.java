package org.janelia.render.service;

import java.util.Collections;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link TileDataService} class.
 *
 * @author Eric Trautman
 */
public class TileDataServiceTest {

    @Test
    public void testGetCoreTileRenderParameters() {

        // from https://github.com/saalfeldlab/render/issues/24
        final String json =
                "{\n" +
                "  \"tileId\" : \"1,3484_aligned_0_1_flip\",\n" +
                "  \"z\" : 3484.0,\n" +
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
        
        final boolean force = true;
        final boolean sloppy = true; // TODO: fix 1-pixel clipped bounding box when sloppy = false
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), force, sloppy);

        RenderParameters renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null, null, null,
                                                            null,
                                                            null, null, null,
                                                            tileSpec);

        assertEquals(1024, renderParameters.getWidth(), "invalid width for tile");
        assertEquals(1024, renderParameters.getHeight(), "invalid height for tile");

        // ---------------------------------------------------
        tileSpec = TileSpec.fromJson(json);

        renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null, null, null,
                                                            true,
                                                            null, null, null,
                                                            tileSpec);

        List<TileSpec> tileSpecs = renderParameters.getTileSpecs();
        assertEquals(1, tileSpecs.size(), "invalid number of tile specs returned after normalization");

        TileSpec flattenedTileSpec = tileSpecs.getFirst();
        ListTransformSpec transforms = flattenedTileSpec.getTransforms();
        assertEquals(1, transforms.size(), "invalid number of transforms after normalization");

        // ---------------------------------------------------
        tileSpec = TileSpec.fromJson(json);

        renderParameters =
                TileDataService.getCoreTileRenderParameters(null, null, null, null, null,
                                                            true,
                                                            Collections.emptySet(), Collections.emptySet(), null,
                                                            tileSpec);

        tileSpecs = renderParameters.getTileSpecs();
        assertEquals(1, tileSpecs.size(), "invalid number of tile specs returned with empty sets");

        flattenedTileSpec = tileSpecs.getFirst();
        transforms = flattenedTileSpec.getTransforms();
        assertEquals(1, transforms.size(), "invalid number of transforms with empty sets");

    }

}
