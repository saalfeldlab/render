package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.File;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.janelia.alignment.json.JsonUtils;
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
public class BoxRemovalClient {

    @SuppressWarnings("ALL")
    private static class Parameters {

        @Parameter(names = "--help", description = "Display this note", help = true)
        private transient boolean help;

        @Parameter(names = "--stackDirectory", description = "Stack directory containing boxes to remove (e.g. /tier2/flyTEM/nobackup/rendered_boxes/FAFB00/v7_align_tps/8192x8192)", required = true)
        private String stackDirectory;

        @Parameter(description = "Z values for layers to remove", required = true)
        private List<Double> zValues;

        private transient JCommander jCommander;

        public String toString() {
            try {
                return JsonUtils.MAPPER.writeValueAsString(this);
            } catch (final JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public void parse(final String[] args) throws IllegalArgumentException {

            jCommander = new JCommander(this);
            jCommander.setProgramName("java -cp current-ws-standalone.jar " + this.getClass().getName());

            try {
                jCommander.parse(args);
            } catch (final Throwable t) {
                throw new IllegalArgumentException("failed to parse command line arguments", t);
            }

            if (help) {
                jCommander.usage();
                System.exit(1);
            }
        }
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

                final BoxRemovalClient client = new BoxRemovalClient(parameters);
                for (final Double z : parameters.zValues) {
                    client.removeBoxesForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private final File boxDirectory;

    public BoxRemovalClient(final Parameters params) {
        this.boxDirectory = new File(params.stackDirectory).getAbsoluteFile();
        if (! boxDirectory.exists()) {
            throw new IllegalArgumentException("missing stack directory " + boxDirectory);
        }
    }

    public void removeBoxesForZ(final Double z)
            throws Exception {

        LOG.info("removeBoxesForZ: entry, z={}", z);

        final String zName = String.valueOf(z.intValue());

        File levelDirectory;
        File zDirectory;
        for (int level = 0; level < 10; level++) {
            levelDirectory = new File(boxDirectory, String.valueOf(level));
            if (levelDirectory.exists()) {
                zDirectory = new File(levelDirectory, zName);
                if (zDirectory.exists()) {
                    LOG.info("removeBoxesForZ: removing {}", zDirectory);
                    FileUtils.deleteDirectory(zDirectory);
                }
            }
        }

        final File smallDirectory = new File(boxDirectory, "small");
        if (smallDirectory.exists()) {
            File overview = new File(smallDirectory, z.intValue() + ".jpg");
            if (overview.exists()) {
                if (! overview.delete()) {
                    LOG.warn("failed to delete {}", overview);
                }
            } else {
                overview = new File(smallDirectory, z.intValue() + ".png");
                if (overview.exists()) {
                    if (! overview.delete()) {
                        LOG.warn("failed to delete {}", overview);
                    }
                }
            }
        }

        final File iGridDirectory = new File(boxDirectory, "0/iGrid");
        if (iGridDirectory.exists()) {
            final File iGrid = new File(iGridDirectory, z + ".iGrid");
            if (iGrid.exists()) {
                if (! iGrid.delete()) {
                    LOG.warn("failed to delete {}", iGrid);
                }
            }
        }

        LOG.info("removeBoxesForZ: exit, z={}", z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxRemovalClient.class);
}
