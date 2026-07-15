package org.janelia.alignment.transform;

import org.junit.Assert;
import org.junit.Test;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Tests the {@link DisplacementFieldTransform} class.
 */
public class DisplacementFieldTransformTest {

    private static final String SAMPLE_URI =
            "file:///tmp/does-not-exist.n5";

    @Test
    public void testDataStringRoundTrip() {
        // init should fail to open the (nonexistent) field, but only after parsing the data string,
        // so a real field is not needed to exercise the parse-and-serialize contract.
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        try {
            transform.init(SAMPLE_URI);
            Assert.fail("expected init to fail loading a nonexistent field");
        } catch (final IllegalArgumentException e) {
            Assert.assertEquals("data string should round-trip even when loading fails",
                                SAMPLE_URI, transform.toDataString());
        }
    }

    @Test
    public void testApplyBeforeInitFails() {
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        try {
            transform.applyInPlace(new double[] {0.0, 0.0});
            Assert.fail("expected applyInPlace to fail before the field is loaded");
        } catch (final IllegalStateException e) {
            Assert.assertTrue("exception should mention initialization",
                              e.getMessage().contains("init"));
        }
    }

    @Test
    public void testImplementsCoordinateTransform() {
        // guards the reflective LeafTransformSpec.newInstance() contract (no-arg constructor + interface)
        final CoordinateTransform transform = new DisplacementFieldTransform();
        Assert.assertNotNull("no-arg constructed instance should exist", transform);
    }
}
