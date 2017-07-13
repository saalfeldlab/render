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
package org.janelia.alignment.spec;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ResolvedTileSpecCollection} class.
 *
 * @author Eric Trautman
 */
public class ResolvedTileSpecCollectionTest {

    private final TemTileSpecValidator validator = new TemTileSpecValidator(0, 10, 1, 9);

    @Test
    public void testFilterInvalidSpecs() throws Exception {

        final List<TransformSpec> transformSpecs = new ArrayList<>();
        transformSpecs.add(getTransformSpec("referenced-1"));
        transformSpecs.add(getTransformSpec("unreferenced-2"));
        final int expectedTransformCountBeforeFilter = transformSpecs.size();
        final int expectedTransformCountAfterFilter = transformSpecs.size() - 1;

        final List<TileSpec> tileSpecs = new ArrayList<>();
        tileSpecs.add(getTileSpec("good-1", false));
        tileSpecs.add(getTileSpec("bad-2", true));
        tileSpecs.add(getTileSpec("good-3", false));
        tileSpecs.add(getTileSpec("bad-4", true));
        tileSpecs.add(getTileSpec("good-5", false));

        final TileSpec tileSpecWithMissingImage = new TileSpec();
        tileSpecWithMissingImage.setTileId("bad-missing-image");
        tileSpecs.add(tileSpecWithMissingImage);

        final int expectedTileCountBeforeFilter = tileSpecs.size();
        final int expectedTileCountAfterFilter = tileSpecs.size() - 3;

        final ResolvedTileSpecCollection collection = new ResolvedTileSpecCollection(transformSpecs, tileSpecs);

        Assert.assertEquals("invalid number of tile specs before filter",
                            expectedTileCountBeforeFilter, collection.getTileCount());
        Assert.assertEquals("invalid number of transform specs before filter",
                            expectedTransformCountBeforeFilter, collection.getTransformCount());

        collection.setTileSpecValidator(validator);
        collection.filterInvalidSpecs();

        Assert.assertEquals("invalid number of tile specs after filter",
                            expectedTileCountAfterFilter, collection.getTileCount());
        Assert.assertEquals("invalid number of transform specs after filter",
                            expectedTransformCountAfterFilter, collection.getTransformCount());
    }

    private TransformSpec getTransformSpec(final String transformId) {
        return new LeafTransformSpec(transformId,
                                     null,
                                     AffineModel2D.class.getName(),
                                     "1 0 0 1 0 0");
    }

    private TileSpec getTileSpec(final String tileId,
                                 final boolean isBad) {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setZ(99.0);

        final ChannelSpec channelSpec = new ChannelSpec();
        final File scaledImagesDir = new File("src/test/resources/mipmap-test/scaled-images");
        channelSpec.putMipmap(0, new ImageAndMask(new File(scaledImagesDir, "col0060_row0140_cam0.tif"),
                                                  new File(scaledImagesDir, "49.134.zip")));
        tileSpec.addChannel(channelSpec);

        if (isBad) {
            tileSpec.setBoundingBox(new Rectangle(-5, 0, 1, 1), RenderParameters.DEFAULT_MESH_CELL_SIZE);
        } else {
            tileSpec.setBoundingBox(new Rectangle(1, 2, 3, 4), RenderParameters.DEFAULT_MESH_CELL_SIZE);
            final TransformSpec transformSpec = new ReferenceTransformSpec("referenced-1");
            tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
        }
        return tileSpec;
    }
}
