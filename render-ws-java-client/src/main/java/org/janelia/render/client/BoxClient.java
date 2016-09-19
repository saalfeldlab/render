package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering uniform (but arbitrarily sized) boxes (derived tiles) to disk for one or more layers.
 * See {@link BoxGenerator} for implementation details.
 *
 * @author Eric Trautman
 */
public class BoxClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends BoxGenerator.Parameters {

        // NOTE: almost everything is defined in BoxGenerator.Parameters

        @Parameter(description = "Z values for layers to render", required = true)
        private List<Double> zValues;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, BoxClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final BoxGenerator boxGenerator = new BoxGenerator(parameters);
                boxGenerator.createEmptyImageFile();

                for (final Double z : parameters.zValues) {
                    boxGenerator.generateBoxesForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClient.class);
}
