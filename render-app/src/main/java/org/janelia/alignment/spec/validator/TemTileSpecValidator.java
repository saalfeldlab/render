package org.janelia.alignment.spec.validator;

import org.janelia.alignment.spec.TileSpec;

/**
 * Tile spec validator instance for fly TEM data.
 *
 * @author Eric Trautman
 */
public class TemTileSpecValidator implements TileSpecValidator {

    final double minCoordinate;
    final double maxCoordinate;
    final double minSize;
    final double maxSize;

    public TemTileSpecValidator() {
        // TODO: confirm default bounding box constraints
        this (-400, 500000, 500, 5000);
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

    /**
     * @param  tileSpec  specification to validate.
     *
     * @throws IllegalArgumentException
     *   if the specification is invalid.
     */
    @Override
    public void validate(final TileSpec tileSpec)
            throws IllegalArgumentException {

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
