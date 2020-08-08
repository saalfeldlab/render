package org.janelia.alignment.match;

import com.google.common.base.Objects;

import java.io.Serializable;

/**
 * A pair of canvas identifiers with {@linkplain Comparable natural ordering}.
 *
 * @author Eric Trautman
 */
public class OrderedCanvasIdPair
        implements Comparable<OrderedCanvasIdPair>, Serializable {

    /** Lesser canvas identifier. */
    private final CanvasId p;

    /** Greater canvas identifier. */
    private final CanvasId q;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private OrderedCanvasIdPair() {
        this.p = null;
        this.q = null;
    }

    /**
     * Constructs an ordered pair.
     *
     * @param  oneId      one canvas identifier.
     * @param  anotherId  another canvas identifier.
     *
     * @throws IllegalArgumentException
     *   if both identifiers are the same.
     */
    public OrderedCanvasIdPair(final CanvasId oneId,
                               final CanvasId anotherId)
            throws IllegalArgumentException {

        final int comparisonResult = oneId.compareTo(anotherId);
        if (comparisonResult < 0) {
            this.p = oneId;
            this.q = anotherId;
        } else if (comparisonResult > 0) {
            this.p = anotherId;
            this.q = oneId;
        } else {
            throw new IllegalArgumentException("both IDs are the same: '" + oneId + "'");
        }
    }

    public CanvasId getP() {
        return p;
    }

    public CanvasId getQ() {
        return q;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OrderedCanvasIdPair that = (OrderedCanvasIdPair) o;
        return Objects.equal(p, that.p) &&
               Objects.equal(q, that.q);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(p, q);
    }

    @Override
    public int compareTo(final OrderedCanvasIdPair that) {
        int result = this.p.compareTo(that.p);
        if (result == 0) {
            result = this.q.compareTo(that.q);
        }
        return result;
    }

    @Override
    public String toString() {
        if ((p.getGroupId() == null) && (q.getGroupId() == null)) {
            return "[\"" + p.getId() + "\", \"" + q.getId() + "\"]";
        } else {
            return "{\"p\": [\"" + p.getGroupId() + "\", \"" + p.getId() + "\"], \"q\": [\"" +
                   q.getGroupId() + "\", \"" + q.getId() + "\"]}";
        }
    }
}
