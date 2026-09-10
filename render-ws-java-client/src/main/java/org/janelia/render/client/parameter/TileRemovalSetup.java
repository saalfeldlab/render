package org.janelia.render.client.parameter;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Setup for removing tiles from multi-SEM stacks.
 * <p>
 * Removal can be driven by:
 * <ul>
 *     <li>peak scan data that identifies the last scan to keep for each slab
 *         (see {@link #getPeakScanJson}).  Scans after the peak are removed from
 *         all stacks for each slab in the data.</li>
 *     <li>explicit lists of the scans and MFOVs to remove from specific stacks
 *         (see {@link #getStackList}).</li>
 * </ul>
 * <pre>
 * {
 *   "owner": "hess_wafers_60_61",
 *   "peakScanJson": "gs://janelia-spark-test/library/hess_wafers_60_61.peak_scan.json",
 *   "project_to_stack_scans": {
 *     "w61_serial_070_to_079": {
 *       "w61_s071_r00_gc_icc_par": [ "scan005" ]
 *     }
 *   },
 *   "project_to_stack_scan_mfovs": {
 *     "w61_serial_170_to_179": {
 *       "w61_s178_r00_gc_icc_par": [ "scan024_m0016", "scan025_m0016" ]
 *     }
 *   }
 * }
 * </pre>
 * Removing whole scans leaves a gap in the z values, so layers are always collapsed for
 * project_to_stack_scans.  Removing MFOVs leaves each layer in place, so project_to_stack_scan_mfovs
 * never collapses on its own.  A stack listed in both maps has its scans and MFOVs removed together
 * in one pass (and is collapsed because whole scans are being removed from it).  Requesting MFOV
 * removal for a scan that is itself being removed is allowed but redundant and is simply skipped
 * (see MultiSEMTileRemovalClient.validateMfovNames).
 * <p>
 * Stacks that do not exist are skipped during removal so that one setup can be shared by
 * runs that work against different subsets of stacks.
 */
public class TileRemovalSetup
        implements Serializable {

    private final String owner;
    private final String peakScanJson;

    @JsonProperty("project_to_stack_scans")
    private final Map<String, Map<String, List<String>>> projectToStackScans;

    @JsonProperty("project_to_stack_scan_mfovs")
    private final Map<String, Map<String, List<String>>> projectToStackScanMfovs;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public TileRemovalSetup() {
        this(null, null, null, null);
    }

    public TileRemovalSetup(final String owner,
                            final String peakScanJson,
                            final Map<String, Map<String, List<String>>> projectToStackScans,
                            final Map<String, Map<String, List<String>>> projectToStackScanMfovs) {
        this.owner = owner;
        this.peakScanJson = peakScanJson;
        this.projectToStackScans = projectToStackScans;
        this.projectToStackScanMfovs = projectToStackScanMfovs;
    }

    public String getOwner() {
        return owner;
    }

    /**
     * @return path (e.g. /groups/hess/peak_scan.json) or Google storage URI
     *         (e.g. gs://janelia-spark-test/library/hess_wafers_60_61.peak_scan.json)
     *         for the peak scan JSON data (or null if no peak based removal is needed).
     */
    public String getPeakScanJson() {
        return peakScanJson;
    }

    /**
     * @return the explicitly listed stacks coupled with the parameters for removing tiles from each of them,
     *         ordered by project and then stack.
     *         A stack listed in both maps is returned once with its scans and MFOVs removed together.
     */
    public List<StackWithRemovalParameters> getStackList()
            throws IllegalArgumentException {

        if (isEmpty(projectToStackScans) && isEmpty(projectToStackScanMfovs)) {
            return Collections.emptyList();
        }

        // merge by stack so that a stack in both maps is only processed once
        final Map<StackId, MultiSEMTileRemovalParameters> stackToRemoval = new TreeMap<>();

        addRemovalNames(projectToStackScans, true, stackToRemoval);
        addRemovalNames(projectToStackScanMfovs, false, stackToRemoval);

        return stackToRemoval.entrySet().stream()
                .map(entry -> new StackWithRemovalParameters(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    public boolean hasPeakScanJson() {
        return (peakScanJson != null) && (! peakScanJson.trim().isEmpty());
    }

    public void validate()
            throws IllegalArgumentException {

        if ((! hasPeakScanJson()) && isEmpty(projectToStackScans) && isEmpty(projectToStackScanMfovs)) {
            throw new IllegalArgumentException(
                    "peakScanJson, project_to_stack_scans, and/or project_to_stack_scan_mfovs must be defined");
        }

        // the owner is not in the peak scan data, so it always has to be defined here
        if ((owner == null) || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("owner must be defined");
        }

        for (final StackWithRemovalParameters stackWithRemoval : getStackList()) {
            stackWithRemoval.validate();
        }
    }

    @Override
    public String toString() {
        return "{owner='" + owner + '\'' +
               ", peakScanJson='" + peakScanJson + '\'' +
               ", project_to_stack_scans=" + projectToStackScans +
               ", project_to_stack_scan_mfovs=" + projectToStackScanMfovs +
               '}';
    }

    /**
     * Records the scans (or scan MFOVs) to remove from each stack in the specified project map.
     *
     * @param  projectToStackNames  map of project names to maps of stack names to scan (or scan MFOV) names.
     * @param  removeWholeScans     true if the names identify whole scans to remove;
     *                              false if they identify MFOVs to remove from a scan.
     *
     * @throws IllegalArgumentException
     *   if the owner, a project name, or a stack name is invalid.
     */
    private void addRemovalNames(final Map<String, Map<String, List<String>>> projectToStackNames,
                                 final boolean removeWholeScans,
                                 final Map<StackId, MultiSEMTileRemovalParameters> stackToRemoval)
            throws IllegalArgumentException {

        if (projectToStackNames != null) {
            for (final Map.Entry<String, Map<String, List<String>>> projectEntry : projectToStackNames.entrySet()) {

                final Map<String, List<String>> stackToNames = projectEntry.getValue();

                if (stackToNames != null) {
                    for (final Map.Entry<String, List<String>> stackEntry : stackToNames.entrySet()) {

                        final StackId stackId = new StackId(owner,
                                                             projectEntry.getKey(),
                                                             stackEntry.getKey());
                        final MultiSEMTileRemovalParameters tileRemoval =
                                stackToRemoval.computeIfAbsent(stackId,
                                                                k -> new MultiSEMTileRemovalParameters());

                        if (removeWholeScans) {
                            tileRemoval.scanNames = stackEntry.getValue();
                            // removing whole scans leaves a gap in the z values,
                            // so the remaining layers always need to be collapsed
                            tileRemoval.collapseStack = true;
                        } else {
                            // MFOV removal leaves every layer in place, so it never collapses on its own
                            // (collapseStack stays false unless whole scans are also removed from this stack)
                            tileRemoval.scanMfovNames = stackEntry.getValue();
                        }
                    }
                }
            }
        }
    }

    private static boolean isEmpty(final Map<String, Map<String, List<String>>> projectToStackNames) {
        return (projectToStackNames == null) || projectToStackNames.isEmpty();
    }
}
