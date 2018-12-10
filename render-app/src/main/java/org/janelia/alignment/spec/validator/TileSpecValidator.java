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

    /**
     * Utility for parsing validator configuration data.
     *
     * @param  dataString  configured data string to parse.
     * @param  names       ordered names of configurable attributes.
     * @param  values      ordered values will be overwritten with any values parsed from the data string.
     */
    static void parseDataString(final String dataString,
                                final String[] names,
                                final double[] values) {
        String trimmedNameAndValue;
        for (final String stringNameAndValue : dataString.split(",")) {
            trimmedNameAndValue = stringNameAndValue.trim();
            for (int i = 0; i < names.length; i++) {
                if (trimmedNameAndValue.startsWith(names[i])) {
                    values[i] = Double.parseDouble(trimmedNameAndValue.substring(names[i].length()));
                }
            }
        }
    }

}
