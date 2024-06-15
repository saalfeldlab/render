package org.janelia.alignment.spec.transfer;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root paths for cluster data.
 *
 * @author Eric Trautman
 */
public class ClusterRootPaths {

    private final String rawDat;
    private final String rawH5;
    private final String alignH5;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private ClusterRootPaths() {
        this(null, null, null);
    }

    public ClusterRootPaths(final String rawDat,
                            final String rawH5,
                            final String alignH5) {
        this.rawDat = rawDat;
        this.rawH5 = rawH5;
        this.alignH5 = alignH5;
    }

    @JsonGetter(value = "raw_dat")
    public String getRawDat() {
        return rawDat;
    }

    @JsonGetter(value = "raw_h5")
    public String getRawH5() {
        return rawH5;
    }

    @JsonGetter(value = "align_h5")
    public String getAlignH5() {
        return alignH5;
    }

    /**
     * @return sorted list of paths to all h5 files in the align_h5 directory.
     *
     * @throws IOException
     *   if the align_h5 directory does not exist.
     */
    @JsonIgnore
    public List<Path> getSortedAlignH5Paths()
            throws IOException {

        LOG.info("getSortedAlignH5Paths: entry");

        if (alignH5 == null) {
            throw new IOException("cluster_root_paths.align_h5 is null");
        }

        final Path rootPath = Paths.get(alignH5);
        if (! Files.exists(rootPath)) {
            throw new IOException("cluster_root_paths.align_h5 '" + alignH5 + "' does not exist");
        }

        // align_h5:
        //   (rootPath): /nrs/fibsem/data/jrc_celegans-20240415/align
        //   (h5 file):    /Merlin-6049/2024/05/09/00/Merlin-6049_24-05-09_000312.uint8.h5

        final List<Path> hourlyDirectories = new ArrayList<>();
        final int hourlyDirectoryDepth = 5;
        final int rootPathDepth = rootPath.getNameCount();

        // Use Files.walk to find all hourly directories then separately go through each directory to list h5 files.
        // This is much faster than using Files.walk to find all h5 files
        // because there are typically tens of thousands of h5 files.

        try (final Stream<Path> stream = Files.walk(rootPath, hourlyDirectoryDepth)) {
            stream.filter(e -> e.getNameCount() - rootPathDepth == hourlyDirectoryDepth)
                    .filter(e -> e.toFile().isDirectory())
                    .sorted() // paths seem to be sorted by name by default but can't find that documented anywhere, so sorting here to be sure
                    .forEach(hourlyDirectories::add);
        }

        LOG.info("getSortedAlignH5Paths: found {} hourly directories in {}", hourlyDirectories.size(), rootPath);

        final List<Path> paths = new ArrayList<>();
        for (final Path hourlyDirectory : hourlyDirectories) {
            final File[] h5Files = hourlyDirectory.toFile().listFiles((dir, name) -> name.endsWith(".h5"));
            if (h5Files != null) {
                paths.addAll(
                        Stream.of(h5Files)
                                .map(File::toPath)
                                .sorted() // listFiles does not guarantee order, so sorting is definitely needed here
                                .collect(Collectors.toList()));
            }
        }

        LOG.info("getSortedAlignH5Paths: exit, found {} h5 files in {}", paths.size(), rootPath);

        return paths;
    }

    public static void main(final String[] args) throws Exception {
        final String alignH5 = args.length == 0 ? "/nrs/fibsem/data/jrc_celegans-20240415/align" : args[0];
        final ClusterRootPaths clusterRootPaths = new ClusterRootPaths(null, null, alignH5);
        clusterRootPaths.getSortedAlignH5Paths().forEach(System.out::println);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterRootPaths.class);
}
