package org.janelia.alignment.match.parameters;

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
            description = "Root directory for saved feature lists (omit if features should be extracted from dynamically rendered canvases)"
    )
    public String rootFeatureDirectory;

    @Parameter(
            names = "--requireStoredFeatures",
            description = "indicates that an exception should be thrown when stored features cannot be found (if omitted, missing features are extracted from dynamically rendered canvases)",
            arity = 0)
    public boolean requireStoredFeatures;

    @Parameter(
            names = { "--maxFeatureCacheGb" },
            description = "Maximum number of gigabytes of features to cache"
    )
    public Integer maxFeatureCacheGb = 2;

    @Parameter(
            names = { "--maxFeatureSourceCacheGb" },
            description = "Maximum number of gigabytes of source image and mask data to cache " +
                          "(note: 15000x15000 Fly EM mask is 500MB while 6250x4000 mask is 25MB)"
    )
    public Integer maxFeatureSourceCacheGb = 2;

    public File getRootFeatureDirectory() {
        File directory = null;
        if (rootFeatureDirectory != null) {
            directory = new File(rootFeatureDirectory).getAbsoluteFile();
        }
        return directory;
    }
}

