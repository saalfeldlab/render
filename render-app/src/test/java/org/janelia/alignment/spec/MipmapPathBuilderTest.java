package org.janelia.alignment.spec;

import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MipmapPathBuilder} class.
 *
 * @author Eric Trautman
 */
public class MipmapPathBuilderTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final MipmapPathBuilder mipmapPathBuilder = new MipmapPathBuilder("/root", 1, "tif");
        final String json = mipmapPathBuilder.toJson();

        Assert.assertNotNull("json generation returned null string", json);

        final MipmapPathBuilder parsedBuilder = MipmapPathBuilder.fromJson(json);
        Assert.assertNotNull("null builder returned from json parse", parsedBuilder);
    }

}
