package org.janelia.alignment.spec.validator;

import org.janelia.alignment.spec.TileSpec;

/**
 * Tile spec validator instance for fly TEM data.
 *
 * @author Eric Trautman
 */
public class TemTileSpecValidator implements TileSpecValidator {

    private double minCoordinate;
    private double maxCoordinate;
    private double minSize;
    private double maxSize;

    /**
     * No-arg constructor that applies default parameters is required for
     * client instantiation via reflection.
     */
    public TemTileSpecValidator() {
        this (0, 400000, 500, 5000);
    }

    public TemTileSpecValidator(final double minCoordinate,
                                final double maxCoordinate,
                                final double minSize,
                                final double maxSize) {
        this.minCoordinate = minCoordinate;
        this.maxCoordinate = maxCoordinate;
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    public double getMinCoordinate() {
        return minCoordinate;
    }

    public double getMaxCoordinate() {
        return maxCoordinate;
    }

    public double getMaxSize() {
        return maxSize;
    }

    public double getMinSize() {
        return minSize;
    }

    @Override
    public String toString() {
        return "{ 'class': \"" + getClass() + "\", \"data\": \"" +
               toDataString() + "\" }";
    }

    @Override
    public void init(final String dataString)
            throws IllegalArgumentException {
        final String[] names = { "minCoordinate:", "maxCoordinate:", "minSize:", "maxSize:" };
        final double[] values = new double[] { minCoordinate, maxCoordinate, minSize, maxSize};

        TileSpecValidator.parseDataString(dataString, names, values);

        minCoordinate = values[0];
        maxCoordinate = values[1];
        minSize = values[2];
        maxSize = values[3];
    }

    @Override
    public String toDataString() {
        return "minCoordinate:" + getMinCoordinate() +
               ",maxCoordinate:" + getMaxCoordinate() +
               ",minSize:" + getMinSize() +
               ",maxSize:" + getMaxSize();
    }

    /**
     * @param  tileSpec  specification to validate.
     *
     * @throws IllegalArgumentException
     *   if the specification is invalid.
     */
    @Override
    public void validate(final TileSpec tileSpec)
            throws IllegalArgumentException {

        try {
            tileSpec.validate();
        } catch (final Throwable t) {
            throw new IllegalArgumentException("core validation failed for tileId '" + tileSpec.getTileId() +
                                               "', cause: " + t.getMessage(), t);
        }

        if (tileSpec.getZ() == null) {
            throw new IllegalArgumentException("z value is missing for tileId '" + tileSpec.getTileId() + "'");
        }

        String errorMessage = null;

        if (tileSpec.getMinX() < minCoordinate) {
            errorMessage = "invalid minX of " + tileSpec.getMinX();
        } else if (tileSpec.getMinY() < minCoordinate) {
            errorMessage = "invalid minY of " + tileSpec.getMinY();
        } else if (tileSpec.getMaxX() > maxCoordinate) {
            errorMessage = "invalid maxX of " + tileSpec.getMaxX();
        } else if (tileSpec.getMaxY() > maxCoordinate) {
            errorMessage = "invalid maxY of " + tileSpec.getMaxY();
        } else {
            final double width = tileSpec.getMaxX() - tileSpec.getMinX();
            final double height = tileSpec.getMaxY() - tileSpec.getMinY();
            if ((width < minSize) || (width > maxSize)) {
                errorMessage = "invalid width of " + width;
            } else if ((height < minSize) || (height > maxSize)) {
                errorMessage = "invalid height of " + height;
            }
        }

        if (errorMessage != null) {
            throw new IllegalArgumentException(
                    errorMessage + " derived for tileId '" + tileSpec.getTileId() +
                    "', bounds are [" + tileSpec.getMinX() + ", " + tileSpec.getMinY() + ", " + tileSpec.getMaxX() +
                    ", " + tileSpec.getMaxY() + "] with transforms " +
                    tileSpec.getTransforms().toJson().replace('\n', ' '));
        }

    }
}
