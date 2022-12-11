package org.janelia.alignment.multisem;

import com.google.common.base.Objects;

import java.io.Serializable;

/**
 * A pair of MFOV identifiers with {@linkplain Comparable natural ordering}.
 *
 * @author Eric Trautman
 */
public class OrderedMFOVPair
        implements Comparable<OrderedMFOVPair>, Serializable {

    /** Lesser MFOV. */
    private final LayerMFOV p;

    /** Greater MFOV. */
    private final LayerMFOV q;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private OrderedMFOVPair() {
        this.p = null;
        this.q = null;
    }

    /**
     * Constructs an ordered pair.
     *
     * @param  oneMFOV      one MFOV.
     * @param  anotherMFOV  another MFOV.
     *
     * @throws IllegalArgumentException
     *   if both identifiers are the same.
     */
    public OrderedMFOVPair(final LayerMFOV oneMFOV,
                           final LayerMFOV anotherMFOV)
            throws IllegalArgumentException {

        final int comparisonResult = oneMFOV.compareTo(anotherMFOV);
        if (comparisonResult < 0) {
            this.p = oneMFOV;
            this.q = anotherMFOV;
        } else if (comparisonResult > 0) {
            this.p = anotherMFOV;
            this.q = oneMFOV;
        } else {
            throw new IllegalArgumentException("both IDs are the same: '" + oneMFOV + "'");
        }
    }

    public LayerMFOV getP() {
        return p;
    }

    public LayerMFOV getQ() {
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
        final OrderedMFOVPair that = (OrderedMFOVPair) o;
        return Objects.equal(p, that.p) &&
               Objects.equal(q, that.q);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(p, q);
    }

    @Override
    public int compareTo(final OrderedMFOVPair that) {
        int result = this.p.compareTo(that.p);
        if (result == 0) {
            result = this.q.compareTo(that.q);
        }
        return result;
    }

    @Override
    public String toString() {
        return p + "::" + q;
    }
}
