package org.janelia.alignment.filter;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link FilterSpec} class.
 *
 * @author Eric Trautman
 */
public class FilterSpecTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final CLAHE originalFilter = new CLAHE(false, 499, 255, 2.2f);

        final FilterSpec filterSpec = FilterSpec.forFilter(originalFilter);

        final String json = filterSpec.toJson();
        Assert.assertNotNull("json generation returned null string", json);

        final FilterSpec parsedSpec = FilterSpec.fromJson(json);
        Assert.assertNotNull("null spec returned from json parse", parsedSpec);

        final Filter parsedInstance = parsedSpec.buildInstance();

        if (parsedInstance instanceof CLAHE) {
            final CLAHE clahe = (CLAHE) parsedInstance;
            Assert.assertEquals("invalid block radius parsed",
                                originalFilter.getBlockRadius(), clahe.getBlockRadius());
        } else {
            Assert.assertEquals("invalid instance created",
                                CLAHE.class, parsedInstance.getClass());
        }

    }

}
