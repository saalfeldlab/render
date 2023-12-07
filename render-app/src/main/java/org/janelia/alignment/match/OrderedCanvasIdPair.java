package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.Objects;

import org.janelia.alignment.spec.TileBounds;

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

    private final Double absoluteDeltaZ;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private OrderedCanvasIdPair() {
        this.p = null;
        this.q = null;
        this.absoluteDeltaZ = null;
    }

    /**
     * Constructs an ordered pair.
     *
     * @param  oneId      one canvas identifier.
     * @param  anotherId  another canvas identifier.
     * @param  deltaZ     delta between z values of both canvases (specify null if irrelevant or 0).
     *
     * @throws IllegalArgumentException
     *   if both identifiers are the same.
     */
    public OrderedCanvasIdPair(final CanvasId oneId,
                               final CanvasId anotherId,
                               final Double deltaZ)
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
        this.absoluteDeltaZ = deltaZ == null ? null : Math.abs(deltaZ);
    }

    /**
     * @param  oneTileBounds      identifiers and bounds for one canvas identifier.
     * @param  anotherTileBounds  identifiers and bounds for another canvas identifier.
     *
     * @return an ordered pair where each CanvasId includes relative position information
     *         based upon the specified tile bounds.
     *         Assumes that the bounds are for the same layer so the pair's deltaZ is set to null.
     *
     * @throws IllegalArgumentException
     *   if both tile identifiers are the same.
     */
    public static OrderedCanvasIdPair withRelativePositions(final TileBounds oneTileBounds,
                                                            final TileBounds anotherTileBounds)
            throws IllegalArgumentException {

        final CanvasId oneCanvasId = new CanvasId(oneTileBounds.getSectionId(), oneTileBounds.getTileId());
        final CanvasId anotherCanvasId = new CanvasId(anotherTileBounds.getSectionId(), anotherTileBounds.getTileId());

        final int comparisonResult = oneCanvasId.compareTo(anotherCanvasId);
        final MontageRelativePosition[] positions = MontageRelativePosition.getRelativePositions(oneTileBounds,
                                                                                                 anotherTileBounds);
        final OrderedCanvasIdPair pair;
        if (comparisonResult < 0) {
            pair = new OrderedCanvasIdPair(oneCanvasId.withRelativePosition(positions[0]),
                                           anotherCanvasId.withRelativePosition(positions[1]),
                                           null);
        } else {
            // flip relative position values since p and q will be flipped by constructor normalization
            pair = new OrderedCanvasIdPair(oneCanvasId.withRelativePosition(positions[1]),
                                           anotherCanvasId.withRelativePosition(positions[0]),
                                           null);
        }
        return pair;
    }

    public CanvasId getP() {
        return p;
    }

    public CanvasId getQ() {
        return q;
    }

    public Double getAbsoluteDeltaZ() {
        return absoluteDeltaZ;
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
        return Objects.equals(p, that.p) &&
               Objects.equals(q, that.q);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p, q);
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
            return "{\"p\": \"" + p + "\", \"q\": \"" + q + "\"}";
    }
}
