package org.janelia.render.client.zspacing;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates layer thickness correction data (typically loaded from a file),
 * providing {@link #getInterpolator} method to facilitate rendering thickness corrected pixel intensities.
 *
 * @author Eric Trautman
 */
public class ThicknessCorrectionData
        implements Serializable {

    /**
     * Encapsulates information needed to render thickness corrected intensities for a single layer.
     */
    public static class LayerInterpolator
            implements Serializable {

        private final long priorStackZ;
        private final double priorWeight;
        private final long nextStackZ;

        /**
         * Constructs an "identity" interpolator that does not interpolate anything.
         *
         * @param  stackZ  render stack z value for the layer.
         */
        public LayerInterpolator(final long stackZ) {
            this.priorStackZ = stackZ;
            this.priorWeight = 1.0;
            this.nextStackZ = stackZ;
        }

        /**
         * Constructs an interpolator for one layer.
         *
         * @param  priorStackZ  stack z for the layer immediately before the corrected z.
         * @param  priorWeight  linear interpolation weight for the prior layer.
         * @param  nextStackZ   stack z for the layer immediately after the corrected z.
         */
        public LayerInterpolator(final long priorStackZ,
                                 final double priorWeight,
                                 final long nextStackZ) {
            if ((priorWeight < 0.0) || (priorWeight > 1.0)) {
                throw new IllegalArgumentException("priorWeight must be between 0 and 1");
            }
            this.priorStackZ = priorStackZ;
            this.priorWeight = priorWeight;
            this.nextStackZ = nextStackZ;
        }

        /**
         * @return stack z for the layer immediately before the corrected z.
         */
        public long getPriorStackZ() {
            return priorStackZ;
        }

        /**
         * @return linear interpolation weight for the prior layer.
         */
        public double getPriorWeight() {
            return priorWeight;
        }

        /**
         * @return stack z for the layer immediately after the corrected z.
         */
        public long getNextStackZ() {
            return nextStackZ;
        }

        /**
         * @return true if this interpolator actually interpolates intensities;
         *         false if it simply returns prior intensity.
         */
        public boolean needsInterpolation() {
            return priorStackZ < nextStackZ;
        }

        /**
         * @param  priorIntensity  pixel intensity from the the priorStackZ layer.
         * @param  nextIntensity   pixel intensity from the the nextStackZ layer.
         *
         * @return linear interpolated intensity using corrected z weight information.
         */
        public double deriveIntensity(final double priorIntensity,
                                      final double nextIntensity) {
            return (priorIntensity * priorWeight) + (nextIntensity * (1 - priorWeight));
        }
    }

    private final List<Double> orderedCorrectedZValues;
    private final List<Integer> orderedStackZValues;

    /**
     * Constructs thickness correction data by loading it from the specified file.
     *
     * @param  zCoordsPath         path of thickness correction data file.
     *
     * @throws IllegalArgumentException
     *   if the loaded data is invalid.
     *
     * @throws IOException
     *   if the data file cannot be read.
     */
    public ThicknessCorrectionData(final String zCoordsPath)
            throws IllegalArgumentException, IOException {
        this(Files.readAllLines(Paths.get(zCoordsPath)));
    }

    /**
     * Constructs thickness correction data by loading it from the specified file.
     *
     * @param  correctionDataLines  ordered list of lines containing space separated correction data
     *                              ([integral z] [corrected z]).
     *
     * @throws IllegalArgumentException
     *   if the loaded data is invalid.
     */
    public ThicknessCorrectionData(final List<String> correctionDataLines)
            throws IllegalArgumentException {

        this.orderedCorrectedZValues = new ArrayList<>();
        this.orderedStackZValues = new ArrayList<>();

        final Pattern linePattern = Pattern.compile("(\\d+)\\s+(.*)");
        Double lastCorrectedZ = null;
        for (int linesIndex = 0; linesIndex < correctionDataLines.size(); linesIndex++) {
            final Matcher matcher = linePattern.matcher(correctionDataLines.get(linesIndex));
            if (matcher.matches()) {
                final Integer stackZ = new Integer(matcher.group(1));
                final Double correctedZ = new Double(matcher.group(2));
                if ((lastCorrectedZ != null) && (correctedZ <= lastCorrectedZ)) {
                    throw new IllegalArgumentException("out of order or duplicate correctedZ value on line "
                                                       + (linesIndex + 1));
                }
                this.orderedCorrectedZValues.add(correctedZ);
                this.orderedStackZValues.add(stackZ);
                lastCorrectedZ = correctedZ;
            }
        }

        if (this.orderedCorrectedZValues.size() == 0) {
            throw new IllegalArgumentException("no corrected z values found");
        }
        
        System.out.println("ThicknessCorrectionData: loaded " + this.orderedCorrectedZValues.size() + " corrected z values");
    }

    /**
     * @param  renderZ  layer being rendered.
     *
     * @return an interpolator instance that contains the information needed to render
     *         thickness corrected intensities for the specified layer.
     */
    public LayerInterpolator getInterpolator(final long renderZ) {

        LayerInterpolator interpolator = null;

        int nextIndex = Collections.binarySearch(orderedCorrectedZValues, (double) renderZ);

        if (nextIndex < 0) {
            // exact corrected z NOT found, negative insertion point returned (see binarySearch javadoc)
            nextIndex = -nextIndex - 1;

            final int priorIndex = nextIndex - 1;

            if (priorIndex >= 0) {
                final double priorCorrectedZ = orderedCorrectedZValues.get(priorIndex);

                double nextCorrectedZ;
                for (; nextIndex < orderedCorrectedZValues.size(); nextIndex++) {
                    nextCorrectedZ = orderedCorrectedZValues.get(nextIndex);
                    if (nextCorrectedZ > renderZ) {
                        final double priorToRenderDistance = renderZ - priorCorrectedZ;
                        final double totalDistance = nextCorrectedZ - priorCorrectedZ;
                        final double priorWeight = 1.0 - (priorToRenderDistance / totalDistance);
                        interpolator = new LayerInterpolator(orderedStackZValues.get(priorIndex),
                                                             priorWeight,
                                                             orderedStackZValues.get(nextIndex));
                        break;
                    }
                }
            }

        } else {
            // exact corrected z found,
            // interpolation is not needed so return "identity" interpolator with corresponding stackZ
            interpolator = new LayerInterpolator(orderedStackZValues.get(nextIndex));
        }

        if (interpolator == null) {
            // renderZ is out of range of loaded data, so return "identity" interpolator with renderZ
            interpolator = new LayerInterpolator(renderZ);
        }

        return interpolator;
    }
}
