package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.Serializable;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for removing images from a CATMAID LargeDataTileSource directory structure that looks like this:
 * <pre>
 *         [root directory]/[tile width]x[tile height]/[level]/[z]/[row]/[col].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class BoxRemovalClient implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @Parameter(
                names = "--stackDirectory",
                description = "Stack directory containing boxes to remove (e.g. /tier2/flyTEM/nobackup/rendered_boxes/FAFB00/v7_align_tps/8192x8192)",
                required = true)
        public String stackDirectory;

        @Parameter(
                names = "--minLevel",
                description = "Minimum mipmap level to remove",
                required = false)
        public int minLevel = 0;

        @Parameter(
                names = "--maxLevel",
                description = "Maximum mipmap level to remove (values > 8 will also delete small overview images)",
                required = false)
        public int maxLevel = 9;

        @Parameter(
                description = "Z values for layers to remove",
                required = true)
        public List<Double> zValues;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxRemovalClient client = new BoxRemovalClient(parameters.stackDirectory,
                                                                     parameters.minLevel,
                                                                     parameters.maxLevel);
                for (final Double z : parameters.zValues) {
                    client.removeBoxesForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private final File boxDirectory;
    private final int minLevel;
    private final int maxLevel;

    public BoxRemovalClient(final String stackDirectory,
                            final int minLevel,
                            final int maxLevel) {
        this.boxDirectory = new File(stackDirectory).getAbsoluteFile();
        if (! boxDirectory.exists()) {
            throw new IllegalArgumentException("missing stack directory " + boxDirectory);
        }
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        if ((minLevel < 0) || (minLevel > maxLevel)) {
            throw new IllegalArgumentException("minLevel of " + minLevel +
                                               " must be > 0 and <= maxLevel of " + maxLevel);
        }
    }

    public void removeBoxesForZ(final Double z)
            throws Exception {

        LOG.info("removeBoxesForZ: entry, z={}", z);

        final String zName = String.valueOf(z.intValue());

        File levelDirectory;
        File zDirectory;
        for (int level = minLevel; level <= maxLevel; level++) {
            levelDirectory = new File(boxDirectory, String.valueOf(level));
            if (levelDirectory.exists()) {

                zDirectory = new File(levelDirectory, zName);
                if (zDirectory.exists()) {
                    LOG.info("removeBoxesForZ: removing {}", zDirectory);
                    FileUtils.deleteDirectory(zDirectory);
                }

                if (level == 0) {
                    final File iGridDirectory = new File(levelDirectory, "iGrid");
                    if (iGridDirectory.exists()) {
                        final File iGrid = new File(iGridDirectory, z + ".iGrid");
                        if (iGrid.exists()) {
                            if (iGrid.delete()) {
                                LOG.info("removeBoxesForZ: removed {}", iGrid);
                            } else {
                                LOG.warn("failed to delete {}", iGrid);
                            }
                        }
                    }
                }

            }

        }

        if (maxLevel > 8) {
            final File smallDirectory = new File(boxDirectory, "small");
            if (smallDirectory.exists()) {
                File overview = new File(smallDirectory, z.intValue() + ".jpg");
                if (overview.exists()) {
                    if (overview.delete()) {
                        LOG.info("removeBoxesForZ: removed {}", overview);
                    } else {
                        LOG.warn("removeBoxesForZ: failed to delete {}", overview);
                    }
                } else {
                    overview = new File(smallDirectory, z.intValue() + ".png");
                    if (overview.exists()) {
                        if (overview.delete()) {
                            LOG.info("removeBoxesForZ: removed {}", overview);
                        } else {
                            LOG.warn("removeBoxesForZ: failed to delete {}", overview);
                        }
                    }
                }
            }
        }

        LOG.info("removeBoxesForZ: exit, z={}", z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxRemovalClient.class);
}
