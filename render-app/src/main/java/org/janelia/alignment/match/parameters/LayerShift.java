package org.janelia.alignment.match.parameters;

import java.io.Serializable;

/**
 * Identifies how many pixels to shift a project stack layer in the x and y directions.
 */
public class LayerShift
        implements Serializable {

    private final String project;
    private final String stack;
    private final double shiftX;
    private final double shiftY;
    private final double z;

    public LayerShift(final String project,
                      final String stack,
                      final double shiftX,
                      final double shiftY,
                      final double z) {
        this.project = project;
        this.stack = stack;
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        this.z = z;
    }

    public String getProject() {
        return project;
    }

    public String getStack() {
        return stack;
    }

    public double getShiftX() {
        return shiftX;
    }

    public double getShiftY() {
        return shiftY;
    }

    public double getZ() {
        return z;
    }

    @Override
    public String toString() {
        return project + "|" + stack + "|" + shiftX + "|" + shiftY + "|" + z;
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
            throw new IllegalArgumentException("shift string '" + shiftString + "' must contain 5 pipe-separated values");
        }

        final double shiftX;
        final double shiftY;
        final double z;
        try {
            shiftX = Double.parseDouble(valueStrings[2]);
            shiftY = Double.parseDouble(valueStrings[3]);
            z = Double.parseDouble(valueStrings[4]);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("last 3 values of shift string '" + shiftString + "' must be doubles for xOffset, yOffset, and z");
        }

        return new LayerShift(valueStrings[0], valueStrings[1], shiftX, shiftY, z);
    }
}
