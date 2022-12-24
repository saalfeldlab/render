package org.janelia.render.client.intensityadjust;

import java.io.Serializable;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for adjusting same z-layer tile intensities for a stack.
 * Results can be stored as tile filters in a result stack or rendered to disk as corrected "montage-scapes".
 *
 * @author Eric Trautman
 */
public class IntensityAdjustedScapeClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final IntensityAdjustParameters parameters = new IntensityAdjustParameters();
                parameters.parse(args);
                parameters.validateAndSetDefaults();

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderDataClient dataClient = parameters.renderWeb.getDataClient();

                final IntensityCorrectionWorker worker = new IntensityCorrectionWorker(parameters, dataClient);

                for (final Double z : worker.getzValues()) {
                    worker.correctZ(dataClient, z);
                }

                worker.completeCorrectedStackAsNeeded(dataClient);

                LOG.info("runClient: exit, corrected {} layers", worker.getzValues().size());
            }
        };
        clientRunner.run();
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityAdjustedScapeClient.class);
}
