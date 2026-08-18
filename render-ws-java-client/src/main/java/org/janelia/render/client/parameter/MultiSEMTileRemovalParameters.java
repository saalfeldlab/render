package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.janelia.alignment.multisem.MultiSemUtilities;

/**
 * Parameters for removing tiles from one multi-SEM stack.
 */
@Parameters
public class MultiSEMTileRemovalParameters
        implements Serializable {

    @Parameter(
            names = "--z",
            description = "z value(s) for layer(s) that should be completely removed " +
                          "(omit if no layers need to be removed)",
            variableArity = true)
    public List<Double> zValues = new ArrayList<>();

    @Parameter(
            names = "--zmfov",
            description = "z value and simple MFOV name for each MFOV that should be removed from one layer " +
                          "(e.g. z1_m0015).  Omit if no MFOVs need to be removed.",
            variableArity = true)
    public List<String> zMfovNames = new ArrayList<>();

    @Parameter(
            names = "--collapseStack",
            description = "Indicates that the z value for each layer after a removed layer should be " +
                          "decreased by one for each removed layer before it " +
                          "(e.g. removing z 3 and 5 from a stack with z 1 through 6 leaves z 1, 2, 3, and 4)")
    public boolean collapseStack = false;

    public MultiSEMTileRemovalParameters() {
    }

    public boolean hasZValues() {
        return (zValues != null) && (! zValues.isEmpty());
    }

    public boolean hasZMfovNames() {
        return (zMfovNames != null) && (! zMfovNames.isEmpty());
    }

    public void validate()
            throws IllegalArgumentException {

        if ((! hasZValues()) && (! hasZMfovNames())) {
            throw new IllegalArgumentException("at least one --z or --zmfov value must be specified");
        }

        if (collapseStack && (! hasZValues())) {
            throw new IllegalArgumentException("--collapseStack requires at least one --z value");
        }

        // build the map to validate the format of all --zmfov values
        getZToMfovNamesMap();
    }

    /**
     * @return distinct sorted z values for the layers that should be removed.
     */
    public List<Double> getSortedZValues() {
        return hasZValues() ?
               zValues.stream().distinct().sorted().collect(Collectors.toList()) :
               new ArrayList<>();
    }

    /**
     * @return map of z values to the simple names of the MFOVs that should be removed from each of those layers.
     *
     * @throws IllegalArgumentException
     *   if any --zmfov value is invalid.
     */
    public Map<Double, Set<String>> getZToMfovNamesMap()
            throws IllegalArgumentException {

        final Map<Double, Set<String>> zToMfovNamesMap = new TreeMap<>();

        if (hasZMfovNames()) {
            for (final String zMfovName : zMfovNames) {

                final Matcher matcher = Z_MFOV_NAME_PATTERN.matcher(zMfovName);
                if (! matcher.matches()) {
                    throw new IllegalArgumentException(
                            "invalid --zmfov value '" + zMfovName + "', values must specify a z value and a " +
                            "simple MFOV name (e.g. z1_m0015)");
                }

                final Double z = Double.parseDouble(matcher.group(1));
                zToMfovNamesMap.computeIfAbsent(z, k -> new TreeSet<>()).add(matcher.group(2));
            }
        }

        return zToMfovNamesMap;
    }

    /**
     * @return true if the specified tile is in one of the specified MFOVs, otherwise false.
     */
    public static boolean isTileInMfovs(final String tileId,
                                        final Set<String> mfovNames)
            throws IllegalArgumentException {
        return mfovNames.contains(MultiSemUtilities.getSimpleMfovForTileId(tileId));
    }

    /**
     * @return the collapsed z value for the specified z
     *         (the specified z decreased by one for each removed layer before it).
     */
    public Double getCollapsedZ(final Double z) {
        int removedLayerCount = 0;
        if (hasZValues()) {
            for (final Double removedZ : zValues) {
                if (removedZ < z) {
                    removedLayerCount++;
                }
            }
        }
        return z - removedLayerCount;
    }

    @Override
    public String toString() {
        return "{zValues=" + zValues +
               ", zMfovNames=" + zMfovNames +
               ", collapseStack=" + collapseStack +
               '}';
    }

    /** Matches --zmfov values like z1_m0015 (and z1.0_m0015). */
    private static final Pattern Z_MFOV_NAME_PATTERN = Pattern.compile("^z(\\d+(?:\\.\\d+)?)_(m\\d{4})$");
}
