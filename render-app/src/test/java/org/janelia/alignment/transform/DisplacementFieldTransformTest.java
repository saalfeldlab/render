package org.janelia.alignment.transform;

import java.nio.file.Path;
import java.util.Arrays;

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
            "file:///tmp/does-not-exist.n5?z=5&scale=8.0&offset=100.0,-50.0&vectorScale=2.0";

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

        // defaults only: scale 1 and offset 0 mean (1,1) reads field (1,1) of z-slice 1, and vector scale 1 means
        // the stored vectors are used as-is apart from the pull-to-push negation
        assertDisplacement(fieldDir, "z=1", new double[] {1.0, 1.0}, -12.0, -112.0);

        // scale 2 halves the query position (so (2,2) reads field (1,1)) and vectorScale 4 quadruples the vectors
        assertDisplacement(fieldDir, "z=1&scale=2.0&vectorScale=4.0",
                           new double[] {2.0, 2.0}, -12.0 * 4, -112.0 * 4);

        // offset shifts the query position, so (2,2) again reads field (1,1)
        assertDisplacement(fieldDir, "z=1&offset=1.0,1.0",
                           new double[] {2.0, 2.0}, -12.0, -112.0);

        // a malformed offset is rejected rather than silently read as one number
        final DisplacementFieldTransform malformed = new DisplacementFieldTransform();
        try {
            malformed.init(fieldDir + "?z=1&offset=1.0");
            Assert.fail("expected init to reject a one-component offset");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue("exception should mention the offset, but was: " + e.getMessage(),
                              e.getMessage().contains("offset"));
        }

        // x and y beyond the field are answered from the mirrored extension rather than failing; just past the last
        // sample (the field is 4 wide, so x=3) the double-mirrored extension repeats that boundary value
        assertDisplacement(fieldDir, "z=1", new double[] {4.0, 1.0}, -14.0, -114.0);

        // z is guarded instead, since an out-of-range slice would read outside the cached image
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        try {
            transform.init(fieldDir + "?z=2");
            Assert.fail("expected init to reject a z index outside the field");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue("exception should mention the z range, but was: " + e.getMessage(),
                              e.getMessage().contains("z range"));
        }
    }

    private static void assertDisplacement(final Path fieldDir,
                                           final String queryString,
                                           final double[] location,
                                           final double expectedDx,
                                           final double expectedDy) {

        final String data = fieldDir + "?" + queryString;
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        transform.init(data);

        // the raw lookup, not apply(): apply() inverts the field, which would move the query off the position
        // whose placement is under test here (and this steep test field is not invertible anyway)
        final double[] vector = new double[2];
        transform.lookUpVector(location, vector);
        Assert.assertEquals("wrong x displacement for " + data, expectedDx, vector[0], 0.0001);
        Assert.assertEquals("wrong y displacement for " + data, expectedDy, vector[1], 0.0001);
    }

    /**
     * The field is a pull map, so applying it means solving {@code t = p + d(t)} rather than evaluating {@code d}
     * at {@code p}. Uses a field with displacement {@code -0.2*(x,y)}, whose exact fixed point is {@code p/1.2} —
     * clearly apart from the first-order answer {@code 0.8*p}.
     */
    @Test
    public void testInvertsFieldByIteration() throws Exception {

        final Path fieldDir = tempFolder.newFolder("rampField").toPath();
        PrecomputedTestVolumes.writeRawVolume(fieldDir,
                                              DataType.FLOAT32,
                                              2,
                                              new long[] {64, 48, 1},
                                              new int[] {64, 48, 1},
                                              new long[] {0, 0, 0},
                                              (x, y, z, c) -> (c == 0) ? x : y);

        // vectorScale carries the 0.2 because the test volume can only hold whole numbers
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        transform.init(fieldDir + "?z=0&vectorScale=0.2");

        final double[] target = transform.apply(new double[] {24.0, 12.0});
        Assert.assertEquals("x should solve t = 24 - 0.2 * t", 20.0, target[0], 0.001);
        Assert.assertEquals("y should solve t = 12 - 0.2 * t", 10.0, target[1], 0.001);

        // the defining property, independent of the analytic solution above
        final double[] vector = new double[2];
        transform.lookUpVector(target, vector);
        Assert.assertEquals("x residual", 0.0, 24.0 + vector[0] - target[0], 0.001);
        Assert.assertEquals("y residual", 0.0, 12.0 + vector[1] - target[1], 0.001);

        // the same field at vectorScale 2 is not invertible (Jacobian norm 2), so the iteration hits its cap:
        // that must warn and return the last estimate rather than throw or hand back a non-number
        final DisplacementFieldTransform steep = new DisplacementFieldTransform();
        steep.init(fieldDir + "?z=0&vectorScale=2.0");
        final double[] estimate = steep.apply(new double[] {24.0, 12.0});
        Assert.assertTrue("a non-converging inversion should still return numbers, but was " +
                          Arrays.toString(estimate),
                          Double.isFinite(estimate[0]) && Double.isFinite(estimate[1]));
    }

    @Test
    public void testMisspelledParameterFails() {
        // since everything but z is optional, a typo would otherwise silently apply the default
        final DisplacementFieldTransform transform = new DisplacementFieldTransform();
        try {
            transform.init("file:///tmp/does-not-exist.n5?z=0&scalex=40.0");
            Assert.fail("expected init to reject the misspelled parameter");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue("exception should name the offending parameter",
                              e.getMessage().contains("scalex"));
        }
    }

    @Test
    public void testImplementsCoordinateTransform() {
        // guards the reflective LeafTransformSpec.newInstance() contract (no-arg constructor + interface)
        final CoordinateTransform transform = new DisplacementFieldTransform();
        Assert.assertNotNull("no-arg constructed instance should exist", transform);
    }
}
