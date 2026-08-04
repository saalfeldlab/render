package org.janelia.alignment.transform;

import java.nio.file.Path;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.precomputed.PrecomputedTestVolumes;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Tests the {@link DisplacementFieldTransform} class.
 */
public class DisplacementFieldTransformTest {

    private static final String SAMPLE_URI =
            "file:///tmp/does-not-exist.n5?scaleIndex=3&zIndex=5&scaleX=8.0&scaleY=8.0";

    @Test
    public void testDataStringRoundTrip() {
        // init should fail to open the (nonexistent) field, but only after parsing the data string,
        // so a real field is not needed to exercise the parse-and-serialize contract.
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        try {
            transform.init(SAMPLE_URI);
            Assert.fail("expected init to fail loading a nonexistent field");
        } catch (final RuntimeException e) {
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

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * End-to-end read of a real Neuroglancer-precomputed field through n5-ng-precomputed: guards the
     * [x,y,z,channel] slicing, both scalings, and (since it actually touches the reader) the n5 version
     * alignment between this reactor and the locally installed n5-ng-precomputed build.
     */
    @Test
    public void testAppliesPrecomputedField() throws Exception {

        // value at (x,y,z) is x+y+10*z for the X component (channel 0) and 100 more for Y (channel 1)
        final Path fieldDir = tempFolder.newFolder("field").toPath();
        PrecomputedTestVolumes.writeRawVolume(fieldDir,
                                              DataType.FLOAT32,
                                              2,
                                              new long[] {4, 3, 2},
                                              new int[] {4, 3, 2},
                                              new long[] {0, 0, 0},
                                              (x, y, z, c) -> x + y + 10 * z + 100 * c);

        // unscaled: full-resolution (1,1) reads field (1,1) of z-slice 1; vectors are negated (pull map) but
        // otherwise used as stored
        assertDisplacement(fieldDir, 0, 1, 1.0, new double[] {1.0, 1.0}, -12.0, -112.0);

        // xyScale 2 halves the query position (so (2,2) reads field (1,1)) and scaleIndex 2 quadruples the vectors
        assertDisplacement(fieldDir, 2, 1, 2.0, new double[] {2.0, 2.0}, -12.0 * 4, -112.0 * 4);
    }

    private static void assertDisplacement(final Path fieldDir,
                                           final int scaleIndex,
                                           final int zIndex,
                                           final double xyScale,
                                           final double[] location,
                                           final double expectedDx,
                                           final double expectedDy) {

        final String data = fieldDir + "?scaleIndex=" + scaleIndex + "&zIndex=" + zIndex +
                            "&scaleX=" + xyScale + "&scaleY=" + xyScale;
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        transform.init(data);

        final double[] displaced = transform.apply(location);
        Assert.assertEquals("wrong x displacement for " + data,
                            location[0] + expectedDx, displaced[0], 0.0001);
        Assert.assertEquals("wrong y displacement for " + data,
                            location[1] + expectedDy, displaced[1], 0.0001);
    }

    @Test
    public void testImplementsCoordinateTransform() {
        // guards the reflective LeafTransformSpec.newInstance() contract (no-arg constructor + interface)
        final CoordinateTransform transform = new DisplacementFieldTransform();
        Assert.assertNotNull("no-arg constructed instance should exist", transform);
    }
}
