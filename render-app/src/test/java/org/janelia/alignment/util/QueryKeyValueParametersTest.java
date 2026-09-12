package org.janelia.alignment.util;

import java.util.Optional;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link QueryKeyValueParameters} class.
 */
public class QueryKeyValueParametersTest {

    private static final String SOURCE = "test-source";

    @Test
    public void testParsesEveryType() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters(
                "s=hello&i=42&l=9999999999&f=1.5&d=2.5&ia=1,2,3&la=1,2,3&fa=1.5,2.5&da=1.5,2.5",
                SOURCE);

        Assert.assertEquals("hello", params.getString("s").orElseThrow());
        Assert.assertEquals(Integer.valueOf(42), params.getInt("i").orElseThrow());
        Assert.assertEquals(Long.valueOf(9999999999L), params.getLong("l").orElseThrow()); // exceeds int range
        Assert.assertEquals(1.5f, params.getFloat("f").orElseThrow(), 0.0001f);
        Assert.assertEquals(2.5, params.getDouble("d").orElseThrow(), 0.0001);
        Assert.assertArrayEquals(new int[] {1, 2, 3}, params.getIntArray("ia").orElseThrow());
        Assert.assertArrayEquals(new long[] {1, 2, 3}, params.getLongArray("la").orElseThrow());
        Assert.assertArrayEquals(new float[] {1.5f, 2.5f}, params.getFloatArray("fa").orElseThrow(), 0.0001f);
        Assert.assertArrayEquals(new double[] {1.5, 2.5}, params.getDoubleArray("da").orElseThrow(), 0.0001);
    }

    @Test
    public void testAbsentKeyReturnsEmptyForEveryType() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("present=1", SOURCE);

        Assert.assertEquals(Optional.empty(), params.getString("missing"));
        Assert.assertEquals(Optional.empty(), params.getInt("missing"));
        Assert.assertEquals(Optional.empty(), params.getLong("missing"));
        Assert.assertEquals(Optional.empty(), params.getFloat("missing"));
        Assert.assertEquals(Optional.empty(), params.getDouble("missing"));
        Assert.assertEquals(Optional.empty(), params.getIntArray("missing"));
        Assert.assertEquals(Optional.empty(), params.getLongArray("missing"));
        Assert.assertEquals(Optional.empty(), params.getFloatArray("missing"));
        Assert.assertEquals(Optional.empty(), params.getDoubleArray("missing"));
    }

    @Test
    public void testMalformedPairFails() {
        try {
            new QueryKeyValueParameters("noEqualsSign", SOURCE);
            Assert.fail("expected a pair without '=' to fail");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("noEqualsSign"));
        }
    }

    @Test
    public void testInvalidNumberFailsForEveryScalarType() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("v=notANumber", SOURCE);
        assertInvalid(() -> params.getInt("v"), "integer");
        assertInvalid(() -> params.getLong("v"), "long");
        assertInvalid(() -> params.getFloat("v"), "float");
        assertInvalid(() -> params.getDouble("v"), "double");
    }

    @Test
    public void testInvalidNumberFailsForEveryArrayType() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("v=1,notANumber", SOURCE);
        assertInvalid(() -> params.getIntArray("v"), "integer");
        assertInvalid(() -> params.getLongArray("v"), "long");
        assertInvalid(() -> params.getFloatArray("v"), "float");
        assertInvalid(() -> params.getDoubleArray("v"), "double");
    }

    private static void assertInvalid(final Runnable action, final String expectedTypeName) {
        try {
            action.run();
            Assert.fail("expected invalid " + expectedTypeName + " value to fail");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue("exception should mention " + expectedTypeName + ", but was: " + e.getMessage(),
                              e.getMessage().contains(expectedTypeName));
        }
    }

    @Test
    public void testValidateKeysRejectsUnknownKey() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("a=1&b=2", SOURCE);
        try {
            params.validateKeys(Set.of("a"));
            Assert.fail("expected unknown key 'b' to fail validation");
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("b"));
        }
        params.validateKeys(Set.of("a", "b")); // should not throw once every key is known
    }
}
