package org.janelia.alignment.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a {@code key=value&key2=value2} style query string into typed accessors.
 * Used both for URI query strings and for the {@code ?key=value&...} suffix of a
 * hand-encoded data string (e.g. a {@code CoordinateTransform} data string).
 * <p>
 * Every accessor returns an empty {@link Optional} for an absent key; callers decide how to handle
 * that (a required parameter throws via {@code orElseThrow}, an optional one falls back via
 * {@code orElse}).
 */
public class QueryKeyValueParameters {

    private final Map<String, String> params = new HashMap<>();
    private final String source;

    /**
     * @param  query   the query string to parse (everything after the {@code ?}, without the {@code ?} itself);
     *                 may be {@code null} or empty, in which case no parameters are present.
     * @param  source  the full original string, included in error messages so they can point back to it.
     */
    public QueryKeyValueParameters(final String query, final String source) {
        this.source = source;
        if (query != null) {
            for (final String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                final int eq = pair.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException(
                            "invalid query parameter '" + pair + "' in '" + source + "'");
                }
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
    }

    public Optional<String> getString(final String key) {
        return Optional.ofNullable(params.get(key));
    }

    /** @throws IllegalArgumentException if present but not a valid integer. */
    public Optional<Integer> getInt(final String key) {
        return getString(key).map(value -> parseInt(key, value));
    }

    /** @throws IllegalArgumentException if present but not a valid long. */
    public Optional<Long> getLong(final String key) {
        return getString(key).map(value -> parseLong(key, value));
    }

    /** @throws IllegalArgumentException if present but not a valid float. */
    public Optional<Float> getFloat(final String key) {
        return getString(key).map(value -> parseFloat(key, value));
    }

    /** @throws IllegalArgumentException if present but not a valid double. */
    public Optional<Double> getDouble(final String key) {
        return getString(key).map(value -> parseDouble(key, value));
    }

    /** @throws IllegalArgumentException if present but contains an invalid integer. */
    public Optional<int[]> getIntArray(final String key) {
        return getString(key).map(value -> parseIntArray(key, value));
    }

    /** @throws IllegalArgumentException if present but contains an invalid long. */
    public Optional<long[]> getLongArray(final String key) {
        return getString(key).map(value -> parseLongArray(key, value));
    }

    /** @throws IllegalArgumentException if present but contains an invalid float. */
    public Optional<float[]> getFloatArray(final String key) {
        return getString(key).map(value -> parseFloatArray(key, value));
    }

    /** @throws IllegalArgumentException if present but contains an invalid double. */
    public Optional<double[]> getDoubleArray(final String key) {
        return getString(key).map(value -> parseDoubleArray(key, value));
    }

    /**
     * @throws IllegalArgumentException if any parsed key is not in {@code validKeys}; helps catch typos that
     *                                  would otherwise silently fall back to a default value.
     */
    public void validateKeys(final Set<String> validKeys) {
        for (final String key : params.keySet()) {
            if (! validKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "unknown query parameter '" + key + "' in '" + source +
                        "'; supported parameters are " + validKeys);
            }
        }
    }

    private int parseInt(final String key, final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid integer value '" + value + "' for parameter '" + key + "' in '" + source + "'", e);
        }
    }

    private long parseLong(final String key, final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid long value '" + value + "' for parameter '" + key + "' in '" + source + "'", e);
        }
    }

    private float parseFloat(final String key, final String value) {
        try {
            return Float.parseFloat(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid float value '" + value + "' for parameter '" + key + "' in '" + source + "'", e);
        }
    }

    private double parseDouble(final String key, final String value) {
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid double value '" + value + "' for parameter '" + key + "' in '" + source + "'", e);
        }
    }

    private int[] parseIntArray(final String key, final String value) {
        final String[] parts = value.split(",");
        final int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseInt(key, parts[i]);
        }
        return result;
    }

    private long[] parseLongArray(final String key, final String value) {
        final String[] parts = value.split(",");
        final long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseLong(key, parts[i]);
        }
        return result;
    }

    private float[] parseFloatArray(final String key, final String value) {
        final String[] parts = value.split(",");
        final float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseFloat(key, parts[i]);
        }
        return result;
    }

    private double[] parseDoubleArray(final String key, final String value) {
        final String[] parts = value.split(",");
        final double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseDouble(key, parts[i]);
        }
        return result;
    }
}
