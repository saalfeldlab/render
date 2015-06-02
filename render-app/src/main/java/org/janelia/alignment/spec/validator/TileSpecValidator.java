package org.janelia.alignment.spec.validator;

import org.janelia.alignment.spec.TileSpec;

/**
 * Common tile specification validation interface.
 *
 * @author Eric Trautman
 */
public interface TileSpecValidator {

    /**
     * @param  tileSpec  specification to validate.
     *
     * @throws IllegalArgumentException
     *   if the specification is invalid.
     */
    public void validate(final TileSpec tileSpec)
            throws IllegalArgumentException;

}
