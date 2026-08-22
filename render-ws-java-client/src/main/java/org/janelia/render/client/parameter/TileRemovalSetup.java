package org.janelia.render.client.parameter;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Setup for removing tiles from multi-SEM stacks.
 * <p>
 * Removal can be driven by:
 * <ul>
 *     <li>peak scan data that identifies the last scan to keep for each slab
 *         (see {@link #getPeakScanJson}).  Scans after the peak are removed from
 *         all stacks for each slab in the data.</li>
 *     <li>an explicit list of stacks with the scans and MFOVs to remove from each
 *         (see {@link #getStackList}).</li>
 * </ul>
 */
public class TileRemovalSetup
        implements Serializable {

    private final String peakScanJson;
    private final List<StackWithRemovalParameters> stackList;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public TileRemovalSetup() {
        this(null, null);
    }

    public TileRemovalSetup(final String peakScanJson,
                            final List<StackWithRemovalParameters> stackList) {
        this.peakScanJson = peakScanJson;
        this.stackList = stackList;
    }

    /**
     * @return path (e.g. /groups/hess/peak_scan.json) or Google storage URI
     *         (e.g. gs://janelia-spark-test/library/hess_wafers_60_61.peak_scan.json)
     *         for the peak scan JSON data (or null if no peak based removal is needed).
     */
    public String getPeakScanJson() {
        return peakScanJson;
    }

    public List<StackWithRemovalParameters> getStackList() {
        return stackList == null ? Collections.emptyList() : stackList;
    }

    public boolean hasPeakScanJson() {
        return (peakScanJson != null) && (! peakScanJson.trim().isEmpty());
    }

    public boolean hasStackList() {
        return ! getStackList().isEmpty();
    }

    public void validate()
            throws IllegalArgumentException {

        if ((! hasPeakScanJson()) && (! hasStackList())) {
            throw new IllegalArgumentException("peakScanJson and/or stackList must be defined");
        }

        for (final StackWithRemovalParameters stackWithRemoval : getStackList()) {
            stackWithRemoval.validate();
        }
    }

    @Override
    public String toString() {
        return "{peakScanJson='" + peakScanJson + '\'' +
               ", stackList=" + stackList +
               '}';
    }
}
