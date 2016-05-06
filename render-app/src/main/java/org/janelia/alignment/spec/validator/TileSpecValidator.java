package org.janelia.alignment.spec.validator;

import org.janelia.alignment.spec.TileSpec;

/**
 * Common tile specification validation interface.
 *
 * @author Eric Trautman
 */
public interface TileSpecValidator {

    /**
     * Initialize validator properties from a data string.
     *
     * @param dataString data string representation of the validator properties.
     *
     * @throws IllegalArgumentException
     *   if the validator cannot be initialized using the specified data string.
     */
    void init(final String dataString)
            throws IllegalArgumentException;

    /**
     * @return a data string representation of the validator properties.
     */
    String toDataString();

    /**
     * @param  tileSpec  specification to validate.
     *
     * @throws IllegalArgumentException
     *   if the specification is invalid.
     */
    void validate(final TileSpec tileSpec)
            throws IllegalArgumentException;

}
