package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.Serializable;

/**
 * Parameters for persisting or retrieving extracted feature data to/from disk.
 *
 * @author Eric Trautman
 */
public class FeatureStorageParameters
        implements Serializable {

    @Parameter(
            names = "--rootFeatureDirectory",
            description = "Root directory for saved feature lists (omit if features should be extracted from dynamically rendered canvases)",
            required = false)
    public String rootFeatureDirectory;

    @Parameter(
            names = "--requireStoredFeatures",
            description = "indicates that an exception should be thrown when stored features cannot be found (if omitted, missing features are extracted from dynamically rendered canvases)",
            required = false,
            arity = 0)
    public boolean requireStoredFeatures;

    @Parameter(
            names = { "--maxFeatureCacheGb" },
            description = "Maximum number of gigabytes of features to cache",
            required = false)
    public Integer maxCacheGb = 2;

    public File getRootFeatureDirectory() {
        File directory = null;
        if (rootFeatureDirectory != null) {
            directory = new File(rootFeatureDirectory).getAbsoluteFile();
        }
        return directory;
    }
}

