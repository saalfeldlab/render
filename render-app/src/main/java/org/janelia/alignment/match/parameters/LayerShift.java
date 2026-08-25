package org.janelia.alignment.match.parameters;

import java.io.Serializable;

import org.jspecify.annotations.NonNull;

/**
 * Identifies how many pixels to shift a project stack layer in the x and y directions.
 */
public record LayerShift(String project, String stack, double xShift, double yShift, double z)
        implements Serializable {

    @Override
    public @NonNull String toString() {
        return project + "|" + stack + "|" + xShift + "|" + yShift + "|" + z;
    }

    public boolean matches(final String project,
                           final String stack) {
        return this.project.equals(project) && this.stack.equals(stack);
    }

    public static LayerShift parse(final String shiftString)
            throws IllegalArgumentException {

        // fields are separated by pipes instead of commas because commas caused problems with command string parsing
        final String[] valueStrings = shiftString.split("\\|");

        if (valueStrings.length != 5) {
            throw new IllegalArgumentException(
                    "shift string '" + shiftString + "' must contain 5 pipe-separated values");
        }

        final double shiftX;
        final double shiftY;
        final double z;
        try {
            shiftX = Double.parseDouble(valueStrings[2]);
            shiftY = Double.parseDouble(valueStrings[3]);
            z = Double.parseDouble(valueStrings[4]);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "last 3 values of shift string '" + shiftString + "' must be doubles for xOffset, yOffset, and z");
        }

        return new LayerShift(valueStrings[0], valueStrings[1], shiftX, shiftY, z);
    }
}
