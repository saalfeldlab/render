package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;

import org.janelia.alignment.json.JsonUtils;

import jakarta.annotation.Nonnull;

import static org.janelia.alignment.match.MontageRelativePosition.LEFT;
import static org.janelia.alignment.match.MontageRelativePosition.TOP;

/**
 * Key identifiers for a canvas.
 *
 * @author Eric Trautman
 */
public class CanvasId
        implements Comparable<CanvasId>, Serializable {

    /** Group (e.g. section) identifier. */
    private final String groupId;

    /** Canvas (e.g. tile) identifier. */
    private final String id;

    /** Position of this canvas relative to a paired montage canvas (or null if not applicable). */
    private final MontageRelativePosition relativePosition;

    /** Full scale x[0] and y[1] offset for all matches derived from clipped canvases. */
    private double[] clipOffsets;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private CanvasId() {
        this(null, null);
    }

    /**
     * Basic constructor.
     *
     * @param  groupId  group (e.g. section) identifier.
     * @param  id       canvas (e.g. tile) identifier.
     */
    public CanvasId(final String groupId,
                    final String id) {
        this(groupId, id, null);
    }

    /**
     * Basic constructor.
     *
     * @param  groupId  group (e.g. section) identifier.
     * @param  id       canvas (e.g. tile) identifier.
     */
    public CanvasId(final String groupId,
                    final String id,
                    final MontageRelativePosition relativePosition) {
        this.groupId = groupId;
        this.id = id;
        this.relativePosition = relativePosition;
        this.clipOffsets = null;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getId() {
        return id;
    }

    public MontageRelativePosition getRelativePosition() {
        return relativePosition;
    }

    public double[] getClipOffsets() {
        return clipOffsets == null ? ZERO_OFFSETS : clipOffsets;
    }

    public void setClipOffsets(final double[] clipOffsets) {
        this.clipOffsets = clipOffsets;
    }

    /**
     * Sets the clip offsets for this canvas.
     *
     * @param  fullWidth   full scale width of the rendered canvas.
     * @param  fullHeight  full scale height of the rendered canvas.
     * @param  clipWidth   width of the full scale clip region (or null if not clipped).
     * @param  clipHeight  height of the full scale clip region (or null if not clipped).
     *
     * @throws IllegalArgumentException
     *   if the canvas' relative position (within a pair) is not known.
     *
     */
    public void setClipOffsets(final Integer fullWidth,
                               final Integer fullHeight,
                               final Integer clipWidth,
                               final Integer clipHeight)
            throws IllegalArgumentException {

        if (relativePosition == null) {
            throw new IllegalArgumentException("cannot set clip offsets for canvas " + this +
                                               " because relative position is unknown");
        }

        clipOffsets = new double[] {0.0, 0.0 };

        if (TOP.equals(relativePosition)) {
            if (clipHeight != null) {
                clipOffsets[1] = fullHeight - clipHeight;
            }
        } else if (LEFT.equals(relativePosition)) {
            if (clipWidth != null) {
                clipOffsets[0] = fullWidth - clipWidth;
            }
        }
    }

    public CanvasId withRelativePosition(final MontageRelativePosition relativePosition) {
        return new CanvasId(groupId, id, relativePosition);
    }

    public CanvasId withoutRelativePosition() {
        return new CanvasId(groupId, id);
    }

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        final CanvasId canvasId = (CanvasId) that;
        return Objects.equals(id, canvasId.id) &&
               Objects.equals(groupId, canvasId.groupId) &&
               Objects.equals(relativePosition, canvasId.relativePosition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, groupId, relativePosition);
    }

    @Override
    public int compareTo(@Nonnull final CanvasId that) {
        return CANVAS_ID_COMPARATOR.compare(this, that);
    }

    @Override
    public String toString() {
        String s = id;
        if (relativePosition != null) {
            s += "::" + relativePosition;
        }
        return s;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<CanvasId> JSON_HELPER =
            new JsonUtils.Helper<>(CanvasId.class);

    public static final double[] ZERO_OFFSETS = { 0.0, 0.0 };

    private static final Comparator<CanvasId> CANVAS_ID_COMPARATOR = Comparator
            .comparing(CanvasId::getGroupId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CanvasId::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CanvasId::getRelativePosition, Comparator.nullsFirst(Comparator.naturalOrder()));

}
