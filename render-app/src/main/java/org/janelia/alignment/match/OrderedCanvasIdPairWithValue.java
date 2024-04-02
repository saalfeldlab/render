package org.janelia.alignment.match;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A pair of canvas identifiers with {@linkplain Comparable natural ordering} and an associated double value (e.g.,
 * a pairwise error).
 *
 * @author Michael Innerberger
 */
public class OrderedCanvasIdPairWithValue implements Serializable {

    private final OrderedCanvasIdPair canvasIdPair;
    private final Double value;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private OrderedCanvasIdPairWithValue() {
        this.canvasIdPair = null;
        this.value = null;
    }

    /**
     * Constructs an ordered pair with associated value.
     *
     * @param  orderedPair  one canvas identifier.
     * @param  value        value that is recorded for the pair.
     */
    public OrderedCanvasIdPairWithValue(final OrderedCanvasIdPair orderedPair, final Double value) {
        this.canvasIdPair = orderedPair;
        this.value = value;
    }

    /**
     * Constructs an ordered pair with associated value.
     *
     * @param  oneId      one canvas identifier.
     * @param  anotherId  another canvas identifier.
     * @param  value      value that should be recorded for the pair.
     *
     * @throws IllegalArgumentException
     *   if both identifiers are the same.
     */
    public OrderedCanvasIdPairWithValue(final CanvasId oneId,
                               final CanvasId anotherId,
                               final Double value)
            throws IllegalArgumentException {

        this(new OrderedCanvasIdPair(oneId, anotherId, null), value);
    }

    public OrderedCanvasIdPair getPair() {
        return canvasIdPair;
    }

    public CanvasId getP() {
        return (canvasIdPair == null) ? null : canvasIdPair.getP();
    }

    public CanvasId getQ() {
        return (canvasIdPair == null) ? null : canvasIdPair.getQ();
    }

    public Double getValue() {
        return value;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OrderedCanvasIdPairWithValue that = (OrderedCanvasIdPairWithValue) o;
        return Objects.equals(canvasIdPair, that.canvasIdPair) &&
               Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canvasIdPair, value);
    }

    @Override
    public String toString() {
        return "Pair: " + canvasIdPair + ", value: " + value;
    }

    public static List<OrderedCanvasIdPairWithValue> fromJsonArray(final Reader json)
            throws IllegalArgumentException {
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, OrderedCanvasIdPairWithValue[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<OrderedCanvasIdPairWithValue> loadList(final String dataFile)
            throws IOException, IllegalArgumentException {

        final List<OrderedCanvasIdPairWithValue> pairsWithValues;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            pairsWithValues = OrderedCanvasIdPairWithValue.fromJsonArray(reader);
        }

        LOG.info("load: exit, loaded {} pairs", pairsWithValues.size());

        return pairsWithValues;
    }

    public static OrderedCanvasIdPairWithValue fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<OrderedCanvasIdPairWithValue> JSON_HELPER =
            new JsonUtils.Helper<>(OrderedCanvasIdPairWithValue.class);

    private static final Logger LOG = LoggerFactory.getLogger(OrderedCanvasIdPairWithValue.class);
}
