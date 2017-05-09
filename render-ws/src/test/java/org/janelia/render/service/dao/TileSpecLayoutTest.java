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
package org.janelia.render.service.dao;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TileSpecLayout} class.
 *
 * @author Eric Trautman
 */
public class TileSpecLayoutTest {

    @Test
    public void testLayoutData() throws Exception {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(EXPECTED_TILE_ID);
        tileSpec.setZ(1234.5);
        final LayoutData layoutData = new LayoutData("s12", "array-a", "camera-b", 4, 5, 6.0, 7.0, 8.0);
        tileSpec.setLayout(layoutData);

        final String json = tileSpec.toJson();

        Assert.assertNotNull("json generation returned null string", json);

        final TileSpec parsedSpec = TileSpec.fromJson(json);

        Assert.assertNotNull("json parse returned null spec", parsedSpec);
        Assert.assertEquals("invalid tileId parsed", EXPECTED_TILE_ID, parsedSpec.getTileId());

        final LayoutData parsedLayoutData = parsedSpec.getLayout();
        Assert.assertNotNull("json parse returned null layout data", parsedLayoutData);
        Assert.assertEquals("bad sectionId value", layoutData.getSectionId(), parsedLayoutData.getSectionId());
        Assert.assertEquals("bad array value", layoutData.getTemca(), parsedLayoutData.getTemca());
        Assert.assertEquals("bad camera value", layoutData.getCamera(), parsedLayoutData.getCamera());
        Assert.assertEquals("bad row value", layoutData.getImageRow(), parsedLayoutData.getImageRow());
        Assert.assertEquals("bad col value", layoutData.getImageCol(), parsedLayoutData.getImageCol());
        Assert.assertEquals("bad stageX value", layoutData.getStageX(), parsedLayoutData.getStageX());
        Assert.assertEquals("bad stageY value", layoutData.getStageY(), parsedLayoutData.getStageY());
        Assert.assertEquals("bad rotation value", layoutData.getRotation(), parsedLayoutData.getRotation());

        parsedSpec.setBoundingBox(new Rectangle(11, 12, 21, 22), parsedSpec.getMeshCellSize());
        final ImageAndMask imageAndMask = new ImageAndMask("src/test/resources/stitch-test/coll0075_row0021_cam1.png", null);
        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, imageAndMask);
        parsedSpec.addChannel(channelSpec);

        final String stackRequestUri = "http://foo";
        String layoutFileFormat = TileSpecLayout.Format.KARSH.formatTileSpec(parsedSpec, stackRequestUri);
        String hackedFileFormat = layoutFileFormat.replaceFirst("\t[^\t]+coll0075_row0021_cam1.png",
                                                                      "\timage.png");
        String expectedLayoutFormat =
                layoutData.getSectionId() + '\t' + tileSpec.getTileId() + "\t1.0\t0.0\t" +
                layoutData.getStageX() + "\t0.0\t1.0\t" + layoutData.getStageY() + '\t' +
                layoutData.getImageCol() + '\t' + layoutData.getImageRow() + '\t' + layoutData.getCamera() +
                "\timage.png\t" + layoutData.getTemca() + '\t' + layoutData.getRotation() + '\t' + tileSpec.getZ() +
                "\thttp://foo/tile/" + tileSpec.getTileId() + "/render-parameters\n";
        Assert.assertEquals("bad layout file format generated", expectedLayoutFormat, hackedFileFormat);

        // test tile spec with affine data
        final List<TransformSpec> list = new ArrayList<>();
        list.add(new LeafTransformSpec(AffineModel2D.class.getName(), "1.0 4.0 2.0 5.0 3.0 6.0"));
        parsedSpec.addTransformSpecs(list);

        layoutFileFormat = TileSpecLayout.Format.KARSH.formatTileSpec(parsedSpec, stackRequestUri);
        hackedFileFormat = layoutFileFormat.replaceFirst("\t[^\t]+coll0075_row0021_cam1.png",
                                                         "\timage.png");
        expectedLayoutFormat =
                layoutData.getSectionId() + '\t' + tileSpec.getTileId() + "\t1.0\t2.0\t3.0\t4.0\t5.0\t6.0\t" +
                layoutData.getImageCol() + '\t' + layoutData.getImageRow() + '\t' + layoutData.getCamera() +
                "\timage.png\t" + layoutData.getTemca() + '\t' + layoutData.getRotation() + '\t' + tileSpec.getZ() +
                "\thttp://foo/tile/" + tileSpec.getTileId() + "/render-parameters\n";

        Assert.assertEquals("bad layout file format generated for affine", expectedLayoutFormat, hackedFileFormat);

        layoutFileFormat = TileSpecLayout.Format.SCHEFFER.formatTileSpec(parsedSpec, stackRequestUri);
        hackedFileFormat = layoutFileFormat.replaceFirst("'[^']+coll0075_row0021_cam1.png",
                                                         "'image.png");
        expectedLayoutFormat =
                "TRANSFORM 'image.png' 1.0 2.0 3.0 4.0 5.0 6.0 " +
                tileSpec.getWidth() + ' ' + tileSpec.getHeight() + '\n';

        Assert.assertEquals("bad SCHEFFER layout file format generated for affine",
                            expectedLayoutFormat, hackedFileFormat);
    }

    private static final String EXPECTED_TILE_ID = "test-tile-id";

}
