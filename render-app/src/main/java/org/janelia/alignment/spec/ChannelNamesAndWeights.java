package org.janelia.alignment.spec;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mapping of channel names to weights with some helper functions.
 *
 * @author Eric Trautman
 */
public class ChannelNamesAndWeights
        implements Serializable {

    private final Map<String, Double> namesToWeightsMap;
    private double weightSum;

    /**
     * Constructs an empty map.
     */
    public ChannelNamesAndWeights() {
        this.namesToWeightsMap = new LinkedHashMap<>(); // keep channel names ordered just in case it matters
        this.weightSum = 0.0;
    }

    /**
     * Constructs a map from a specification string that has one of the following formats:
     * <pre>
     *     [channel name]
     *     [channel name]__[weight]
     *     [channel one name]__[weight]__ ... [channel n name]__[weight]
     * </pre>
     *
     * In the following example the 'DAPI' channel will be averaged at 90% and the TdTomato channel at 10%:
     * <pre>
     *     DAPI__0.9__TdTomato__0.1
     * </pre>
     *
     * Weights are expected to range between 0.0 and 1.0.
     * The sum of all weights must be less than 1.0.
     *
     * @param  spec  text specification of the channels and weights.
     *
     * @return a map parsed from the specification.
     *
     * @throws IllegalArgumentException
     *   if the specification cannot be parsed or the sum of all weights is greater than 1.0.
     */
    public static ChannelNamesAndWeights fromSpec(final String spec)
            throws IllegalArgumentException {

        final ChannelNamesAndWeights channelNamesAndWeights = new ChannelNamesAndWeights();

        if (spec == null) {

            channelNamesAndWeights.add(null, 1.0);

        } else {

            final String[] fields = FIELD_SEPARATOR_PATTERN.split(spec);

            if (fields.length > 1) {

                double weight;
                for (int i = 1; i < fields.length; i = i + 2) {
                    weight = Double.parseDouble(fields[i]);
                    channelNamesAndWeights.add(fields[i - 1], weight);
                }

            } else {
                channelNamesAndWeights.add(fields[0], 1.0);
            }
        }

        return channelNamesAndWeights;
    }

    /**
     * @return set of channel names.
     */
    public Set<String> getNames() {
        return namesToWeightsMap.keySet();
    }

    /**
     * @return weight for the specified channel.
     */
    public Double getWeight(final String name) {
        return namesToWeightsMap.get(name);
    }

    /**
     * Adds the channel weight information to this map.
     *
     * @param  channelName  channel name.
     * @param  weight       channel weight.
     *
     * @throws IllegalArgumentException
     *   if the sum of all weights will exceed 1.0 after adding this channel to the map.
     */
    public void add(final String channelName,
                    final double weight)
            throws IllegalArgumentException {

        final double updatedSum = weightSum + weight;

        if (updatedSum > 1.0) {
            throw new IllegalArgumentException("Channel " + channelName + " weight " + weight + " is too large.  " +
                                               "The sum of all weights must be less than or equal to 1.0.  " +
                                               "Current weights are: " + namesToWeightsMap);
        }

        namesToWeightsMap.put(channelName, weight);
        weightSum = updatedSum;
    }

    /**
     * @return number of mapped channels.
     */
    public int size() {
        return namesToWeightsMap.size();
    }

    private static final Pattern FIELD_SEPARATOR_PATTERN = Pattern.compile("__");

}
