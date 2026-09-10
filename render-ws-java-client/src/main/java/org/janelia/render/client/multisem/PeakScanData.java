package org.janelia.render.client.multisem;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudStorageURI;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudUtils;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.googlecloud.GoogleCloudStorageKeyValueAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last (peak) scan that should be kept for each slab in each project.
 * <p>
 * The owner is not part of this data because it is defined by whatever references it
 * (see {@link org.janelia.render.client.parameter.TileRemovalSetup}).
 * <p>
 * The JSON representation looks like:
 * <pre>
 * {
 *   "project_to_slab_peak_scan": {
 *     "w61_serial_070_to_079": {
 *       "w61_s070": 82,
 *       "w61_s072": 76,
 *       "w61_s073": null
 *     }
 *   }
 * }
 * </pre>
 * A null peak scan (e.g. w61_s073 above) means that nothing should be removed from that slab's stacks.
 */
public class PeakScanData
        implements Serializable {

    @JsonProperty("project_to_slab_peak_scan")
    private final Map<String, Map<String, Integer>> projectToSlabPeakScan;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    public PeakScanData() {
        this(null);
    }

    public PeakScanData(final Map<String, Map<String, Integer>> projectToSlabPeakScan) {
        this.projectToSlabPeakScan = projectToSlabPeakScan;
    }

    public Set<String> getProjectNames() {
        return projectToSlabPeakScan == null ?
               Collections.emptySet() : new TreeSet<>(projectToSlabPeakScan.keySet());
    }

    public Map<String, Integer> getSlabToPeakScan(final String project) {
        final Map<String, Integer> slabToPeakScan =
                projectToSlabPeakScan == null ? null : projectToSlabPeakScan.get(project);
        return slabToPeakScan == null ? Collections.emptyMap() : slabToPeakScan;
    }

    /**
     * @param  project  name of the project that contains the stack.
     * @param  stack    name of the stack (e.g. w61_s070_r00_gc_icc).
     *
     * @return the peak scan for the slab that prefixes the specified stack name or null if the stack
     *         should be skipped (its slab is not in the data or its slab has a null peak scan).
     */
    public Integer getPeakScanForStack(final String project,
                                       final String stack) {
        Integer peakScan = null;
        for (final Map.Entry<String, Integer> entry : getSlabToPeakScan(project).entrySet()) {
            final String slab = entry.getKey();
            if (stack.equals(slab) || stack.startsWith(slab + "_")) {
                peakScan = entry.getValue();
                break;
            }
        }
        return peakScan;
    }

    public void validate()
            throws IllegalArgumentException {

        if (getProjectNames().isEmpty()) {
            throw new IllegalArgumentException("project_to_slab_peak_scan must contain at least one project");
        }

        // NOTE: slabs with a null peak scan are allowed and are simply skipped during removal
        for (final String project : getProjectNames()) {
            if (getSlabToPeakScan(project).isEmpty()) {
                throw new IllegalArgumentException("project " + project + " must contain at least one slab");
            }
        }
    }

    @Override
    public String toString() {
        return "{projectToSlabPeakScan=" + projectToSlabPeakScan + '}';
    }

    /**
     * @param  location  path (e.g. /groups/hess/peak_scan.json) or
     *                   Google storage URI (e.g. gs://janelia-spark-test/library/peak_scan.json)
     *                   for the peak scan JSON data.
     *
     * @return validated peak scan data read from the specified location.
     *
     * @throws IOException
     *   if the data cannot be read or parsed.
     */
    public static PeakScanData fromJson(final String location)
            throws IOException {

        LOG.info("fromJson: entry, location={}", location);

        final PeakScanData peakScanData = location.startsWith(GOOGLE_STORAGE_SCHEME) ?
                                          fromGoogleStorageJson(location) : fromFileSystemJson(location);

        if (peakScanData == null) {
            throw new IOException("failed to parse peak scan data from " + location);
        }

        try {
            peakScanData.validate();
        } catch (final IllegalArgumentException e) {
            throw new IOException("invalid peak scan data in " + location + ": " + e.getMessage(), e);
        }

        LOG.info("fromJson: exit, read {} project(s) from {}",
                 peakScanData.getProjectNames().size(), location);

        return peakScanData;
    }

    private static PeakScanData fromGoogleStorageJson(final String location)
            throws IOException {

        final URI uri = URI.create(location);
        final KeyValueAccess keyValueAccess =
                new GoogleCloudStorageKeyValueAccess(GoogleCloudUtils.createGoogleCloudStorage(null),
                                                     new GoogleCloudStorageURI(uri),
                                                     false);

        // NOTE: the channel must be closed along with the reader to release everything it tracks
        try (final LockedChannel lockedChannel = keyValueAccess.lockForReading(uri.getPath());
             final Reader reader = lockedChannel.newReader()) {
            return JSON_HELPER.fromJson(reader);
        }
    }

    private static PeakScanData fromFileSystemJson(final String location)
            throws IOException {
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(location)) {
            return JSON_HELPER.fromJson(reader);
        }
    }

    private static final String GOOGLE_STORAGE_SCHEME = "gs://";

    private static final JsonUtils.Helper<PeakScanData> JSON_HELPER =
            new JsonUtils.Helper<>(PeakScanData.class);

    private static final Logger LOG = LoggerFactory.getLogger(PeakScanData.class);
}
