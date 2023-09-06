package org.janelia.render.client.intensityadjust;

import java.io.Serializable;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for adjusting same z-layer tile intensities for a stack.
 * Results are stored as tile filters in a result stack.
 *
 * @author Eric Trautman
 */
public class IntensityCorrectionClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final IntensityAdjustParameters parameters = new IntensityAdjustParameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderDataClient dataClient = parameters.renderWeb.getDataClient();

                final IntensityCorrectionWorker worker = new IntensityCorrectionWorker(parameters, dataClient);

                // TODO: revisit this once we decide how to partition cross layer runs
                final List<Double> zValues = worker.getzValues();
                worker.correctZRange(dataClient,
                                     zValues.get(0),
                                     zValues.get(zValues.size()-1));

                worker.completeCorrectedStackAsNeeded(dataClient);

                LOG.info("runClient: exit, corrected {} layers", worker.getzValues().size());
            }
        };
        clientRunner.run();
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionClient.class);
}
