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
 * <p>
 * Scans are identified by name instead of by z value so that removal is idempotent
 * (scan names stay the same when layers are removed and renumbered).
 */
@Parameters
public class MultiSEMTileRemovalParameters
        implements Serializable {

    @Parameter(
            names = "--scan",
            description = "scan name(s) for layer(s) that should be completely removed (e.g. scan004).  " +
                          "Omit if no layers need to be removed.",
            variableArity = true)
    public List<String> scanNames = new ArrayList<>();

    @Parameter(
            names = "--scanMfov",
            description = "scan name and simple MFOV name for each MFOV that should be removed from one layer " +
                          "(e.g. scan004_m0015).  Omit if no MFOVs need to be removed.",
            variableArity = true)
    public List<String> scanMfovNames = new ArrayList<>();

    @Parameter(
            names = "--collapseStack",
            description = "Indicates that the z value for each layer after a removed layer should be " +
                          "decreased by one for each removed layer before it " +
                          "(e.g. removing z 3 and 5 from a stack with z 1 through 6 leaves z 1, 2, 3, and 4)")
    public boolean collapseStack = false;

    public MultiSEMTileRemovalParameters() {
    }

    public boolean hasScanNames() {
        return (scanNames != null) && (! scanNames.isEmpty());
    }

    public boolean hasScanMfovNames() {
        return (scanMfovNames != null) && (! scanMfovNames.isEmpty());
    }

    public void validate()
            throws IllegalArgumentException {

        if ((! hasScanNames()) && (! hasScanMfovNames())) {
            throw new IllegalArgumentException("at least one --scan or --scanMfov value must be specified");
        }

        if (collapseStack && (! hasScanNames())) {
            throw new IllegalArgumentException("--collapseStack requires at least one --scan value");
        }

        if (hasScanNames()) {
            for (final String scanName : scanNames) {
                if (! SCAN_NAME_PATTERN.matcher(scanName).matches()) {
                    throw new IllegalArgumentException(
                            "invalid --scan value '" + scanName + "', values must be scan names (e.g. scan004)");
                }
            }
        }

        // build the map to validate the format of all --scanMfov values
        getScanToMfovNamesMap();
    }

    /**
     * @return distinct sorted scan names for the layers that should be removed.
     */
    public List<String> getSortedScanNames() {
        return hasScanNames() ?
               scanNames.stream().distinct().sorted().collect(Collectors.toList()) :
               new ArrayList<>();
    }

    /**
     * @return map of scan names to the simple names of the MFOVs
     *         that should be removed from each of those layers.
     *
     * @throws IllegalArgumentException
     *   if any --scanMfov value is invalid.
     */
    public Map<String, Set<String>> getScanToMfovNamesMap()
            throws IllegalArgumentException {

        final Map<String, Set<String>> scanToMfovNamesMap = new TreeMap<>();

        if (hasScanMfovNames()) {
            for (final String scanMfovName : scanMfovNames) {

                final Matcher matcher = SCAN_MFOV_NAME_PATTERN.matcher(scanMfovName);
                if (! matcher.matches()) {
                    throw new IllegalArgumentException(
                            "invalid --scanMfov value '" + scanMfovName + "', values must specify a scan name " +
                            "and a simple MFOV name (e.g. scan004_m0015)");
                }

                scanToMfovNamesMap.computeIfAbsent(matcher.group(1), k -> new TreeSet<>()).add(matcher.group(2));
            }
        }

        return scanToMfovNamesMap;
    }

    /**
     * @return all distinct scan names referenced by the --scan and --scanMfov values.
     */
    public Set<String> buildScanNamesSet() {
        final Set<String> allScanNames = new TreeSet<>(getSortedScanNames());
        allScanNames.addAll(getScanToMfovNamesMap().keySet());
        return allScanNames;
    }

    /**
     * @return true if the specified tile is in one of the specified MFOVs, otherwise false.
     */
    public static boolean isTileInMfovs(final String tileId,
                                        final Set<String> mfovNames)
            throws IllegalArgumentException {
        return mfovNames.contains(MultiSemUtilities.getSimpleMfovForTileId(tileId));
    }

    @Override
    public String toString() {
        return "{scanNames=" + scanNames +
               ", scanMfovNames=" + scanMfovNames +
               ", collapseStack=" + collapseStack +
               '}';
    }

    /** Matches --scan values like scan004 (and sc01234). */
    private static final Pattern SCAN_NAME_PATTERN = Pattern.compile("^sc[^_]+$");

    /** Matches --scanMfov values like scan004_m0015 (and sc01234_m0015). */
    private static final Pattern SCAN_MFOV_NAME_PATTERN = Pattern.compile("^(sc[^_]+)_(m\\d{4})$");
}
