package org.janelia.alignment.util;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

        assertEquals("hello", params.getString("s").orElseThrow());
        assertEquals(Integer.valueOf(42), params.getInt("i").orElseThrow());
        assertEquals(Long.valueOf(9999999999L), params.getLong("l").orElseThrow()); // exceeds int range
        assertEquals(1.5f, params.getFloat("f").orElseThrow(), 0.0001f);
        assertEquals(2.5, params.getDouble("d").orElseThrow(), 0.0001);
        assertArrayEquals(new int[] {1, 2, 3}, params.getIntArray("ia").orElseThrow());
        assertArrayEquals(new long[] {1, 2, 3}, params.getLongArray("la").orElseThrow());
        assertArrayEquals(new float[] {1.5f, 2.5f}, params.getFloatArray("fa").orElseThrow(), 0.0001f);
        assertArrayEquals(new double[] {1.5, 2.5}, params.getDoubleArray("da").orElseThrow(), 0.0001);
    }

    @Test
    public void testAbsentKeyReturnsEmptyForEveryType() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("present=1", SOURCE);

        assertEquals(Optional.empty(), params.getString("missing"));
        assertEquals(Optional.empty(), params.getInt("missing"));
        assertEquals(Optional.empty(), params.getLong("missing"));
        assertEquals(Optional.empty(), params.getFloat("missing"));
        assertEquals(Optional.empty(), params.getDouble("missing"));
        assertEquals(Optional.empty(), params.getIntArray("missing"));
        assertEquals(Optional.empty(), params.getLongArray("missing"));
        assertEquals(Optional.empty(), params.getFloatArray("missing"));
        assertEquals(Optional.empty(), params.getDoubleArray("missing"));
    }

    @Test
    public void testMalformedPairFails() {
        try {
            new QueryKeyValueParameters("noEqualsSign", SOURCE);
            fail("expected a pair without '=' to fail");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("noEqualsSign"));
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
            fail("expected invalid " + expectedTypeName + " value to fail");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(expectedTypeName), "exception should mention " + expectedTypeName + ", but was: " + e.getMessage());
        }
    }

    @Test
    public void testValidateKeysRejectsUnknownKey() {
        final QueryKeyValueParameters params = new QueryKeyValueParameters("a=1&b=2", SOURCE);
        try {
            params.validateKeys(Set.of("a"));
            fail("expected unknown key 'b' to fail validation");
        } catch (final IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("b"));
        }
        params.validateKeys(Set.of("a", "b")); // should not throw once every key is known
    }
}
