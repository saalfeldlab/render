package org.janelia.alignment.match;

import java.util.Comparator;

import org.janelia.alignment.spec.Bounds;

/**
 * The relative position of one canvas to another.
 *
 * @author Eric Trautman
 */
public enum MontageRelativePosition {
    TOP, BOTTOM, LEFT, RIGHT;

    /**
     * Uses minX and minY of each canvas to determine their relative positions.
     * Orientation (left/right vs. top/bottom) is chosen based upon the largest
     * dimensional distance.
     *
     * @param  pBounds  first canvas bounds.
     * @param  qBounds  second canvas bounds.
     *
     * @return relative positions of the specified canvases.
     */
    public static MontageRelativePosition[] getRelativePositions(final Bounds pBounds,
                                                                 final Bounds qBounds) {

        final MontageRelativePosition[] relativePositions;

        final double deltaX = pBounds.getMinX() - qBounds.getMinX();
        final double deltaY = pBounds.getMinY() - qBounds.getMinY();

        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            if (deltaX > 0) {
                relativePositions = new MontageRelativePosition[] {RIGHT, LEFT };
            } else {
                relativePositions = new MontageRelativePosition[] {LEFT, RIGHT };
            }
        } else {
            if (deltaY > 0) {
                relativePositions = new MontageRelativePosition[] {BOTTOM, TOP };
            } else {
                relativePositions = new MontageRelativePosition[] {TOP, BOTTOM };
            }
        }

        return relativePositions;
    }

    public MontageRelativePosition getOpposite() {
        final MontageRelativePosition opposite;
        switch (this) {
            case TOP: opposite = BOTTOM; break;
            case BOTTOM: opposite = TOP; break;
            case LEFT: opposite = RIGHT; break;
            case RIGHT: opposite = LEFT; break;
            default: throw new IllegalStateException("opposite of " + this + " is not mapped");
        }
        return opposite;
    }

    public static final Comparator<MontageRelativePosition> NULLS_FIRST_POSITION_COMPARATOR =
            Comparator.nullsFirst(Comparator.naturalOrder());
}
