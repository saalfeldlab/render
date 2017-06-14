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
package org.janelia.alignment;

import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the {@link RenderParameters} class.
 *
 * @author Eric Trautman
 */
public class RenderParametersTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final String url = "file:///Users/trautmane/renderer-test.json";
        final double x = 0.0;
        final double y = 1.0;
        final int width = 2;
        final int height = 3;
        final double scale = 0.125;

        final RenderParameters parameters = new RenderParameters(url, x, y, width, height, scale);

        final ChannelSpec channelSpec0 = new ChannelSpec();
        channelSpec0.putMipmap(0, new ImageAndMask("spec0-level0.png", null));
        channelSpec0.putMipmap(1, new ImageAndMask("spec0-level1.png", null));
        channelSpec0.putMipmap(2, new ImageAndMask("spec0-level2.png", null));

        final TileSpec tileSpec0 = new TileSpec();
        tileSpec0.addChannel(channelSpec0);

        List<TransformSpec> transformSpecList = new ArrayList<>();
        transformSpecList.add(new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D", "1 0 0 1 0 0"));

        tileSpec0.addTransformSpecs(transformSpecList);

        parameters.addTileSpec(tileSpec0);

        final ChannelSpec channelSpec1 = new ChannelSpec();
        channelSpec1.putMipmap(0, new ImageAndMask("spec1-level0.png", null));

        final TileSpec tileSpec1 = new TileSpec();
        tileSpec1.addChannel(channelSpec1);

        transformSpecList = new ArrayList<>();
        transformSpecList.add(new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D", "1 0 0 1 1650 0"));

        tileSpec1.addTransformSpecs(transformSpecList);

        parameters.addTileSpec(tileSpec1);

        final String json = parameters.toJson();

        Assert.assertNotNull("json not generated", json);

        final RenderParameters parsedParameters = RenderParameters.parseJson(json);

        Assert.assertNotNull("json parse returned null parameters", parsedParameters);
        Assert.assertEquals("invalid width parsed", width, parsedParameters.getWidth());

        final List<TileSpec> parsedTileSpecs = parsedParameters.getTileSpecs();
        Assert.assertNotNull("json parse returned null tileSpecs", parsedTileSpecs);
        Assert.assertEquals("invalid number of tileSpecs parsed", 2, parsedTileSpecs.size());

        Assert.assertFalse("mipmapPathBuilder should NOT be defined", parsedParameters.hasMipmapPathBuilder());
    }

    @Test
    public void testMergeParameters() throws Exception {

        final String overrideOut = "test-out.jpg";
        final String overrideScale = "0.3";
        final String[] args = {
                "--parameters_url", "src/test/resources/render-parameters-test/render.json",
                "--out", overrideOut,
                "--scale", overrideScale
        };
        final RenderParameters parameters = RenderParameters.parseCommandLineArgs(args);

        Assert.assertEquals("invalid out parameter",
                            overrideOut, parameters.getOut());
        Assert.assertEquals("invalid scale parameter",
                            overrideScale, String.valueOf(parameters.getScale()));
        Assert.assertEquals("x parameter not loaded from parameters_url file",
                            "1.0", String.valueOf(parameters.getX()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExtraneousComma() throws Exception {
        final File jsonFile = new File("src/test/resources/render-parameters-test/extraneous-comma-render.json");
        RenderParameters.parseJson(jsonFile);
    }

}
