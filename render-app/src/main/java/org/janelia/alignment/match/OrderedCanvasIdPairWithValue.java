package org.janelia.alignment.match;

import java.io.Serializable;
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
}
