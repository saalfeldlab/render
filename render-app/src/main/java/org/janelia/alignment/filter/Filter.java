package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.io.Serializable;
import java.util.Map;

/**
 * Common interface for all filter implementations.
 */
public interface Filter extends Serializable {

    /**
     * Initialize this filter's parameters.
     *
     * @param  params  parameters to use.
     */
    void init(final Map<String, String> params);

    /**
     * @return map of this filter's parameters (suitable for specification serialization).
     */
    Map<String, String> toParametersMap();

    /**
     * Apply this filter.
     *
     * @param  ip     pixels to process.
     * @param  scale  current render scale.
     *
     * @return filtered image.
     */
    ImageProcessor process(final ImageProcessor ip,
                           final double scale);


    // Utility methods for parameter parsing ...

    static String getStringParameter(final String parameterName,
                                     final Map<String, String> params)
            throws IllegalArgumentException {
        final String valueString = params.get(parameterName);
        if (valueString == null) {
            throw new IllegalArgumentException("'" + parameterName + "' is not defined");
        }
        return valueString;
    }

    static boolean getBooleanParameter(final String parameterName,
                                       final Map<String, String> params)
            throws IllegalArgumentException {
        final String valueString = getStringParameter(parameterName, params);
        try {
            return Boolean.parseBoolean(valueString);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse '" + parameterName + "' parameter", t);
        }
    }

    static Integer getIntegerParameter(final String parameterName,
                                       final Map<String, String> params) {
        final String valueString = getStringParameter(parameterName, params);
        try {
            return Integer.parseInt(valueString);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse '" + parameterName + "' parameter", t);
        }
    }

    static Float getFloatParameter(final String parameterName,
                                   final Map<String, String> params) {
        final String valueString = getStringParameter(parameterName, params);
        try {
            return Float.parseFloat(valueString);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse '" + parameterName + "' parameter", t);
        }
    }

    static Double getDoubleParameter(final String parameterName,
                                     final Map<String, String> params) {
        final String valueString = getStringParameter(parameterName, params);
        try {
            return Double.parseDouble(valueString);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to parse '" + parameterName + "' parameter", t);
        }
    }

}
