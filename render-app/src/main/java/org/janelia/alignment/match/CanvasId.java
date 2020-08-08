package org.janelia.alignment.match;

import com.google.common.base.Objects;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

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
    private MontageRelativePosition relativePosition;

    /** Full scale x[0] and y[1] offset for all matches derived from clipped canvases. */
    private double[] clipOffsets;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private CanvasId() {
        this.groupId = null;
        this.id = null;
        this.relativePosition = null;
        this.clipOffsets = null;
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

    public void setRelativePosition(final MontageRelativePosition relativePosition) {
        this.relativePosition = relativePosition;
    }

    public double[] getClipOffsets() {
        return clipOffsets == null ? ZERO_OFFSETS : clipOffsets;
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

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        final CanvasId canvasId = (CanvasId) that;
        return Objects.equal(id, canvasId.id) &&
               Objects.equal(groupId, canvasId.groupId) &&
               Objects.equal(relativePosition, canvasId.relativePosition);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, groupId, relativePosition);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public int compareTo(final CanvasId that) {
        int result = this.groupId.compareTo(that.groupId);
        if (result == 0) {
            result = this.id.compareTo(that.id);
            if (result == 0) {
                if (this.relativePosition == null) {
                    result = that.relativePosition == null ? 0 : -1;
                } else if (that.relativePosition != null) {
                    result = this.relativePosition.compareTo(that.relativePosition);
                }
            }
        }
        return result;
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

}
