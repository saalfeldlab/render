package org.janelia.render.client;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link SubstrateMaskGeneratorClient} class.
 *
 * @author Eric Trautman
 */
public class SubstrateMaskGeneratorClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SubstrateMaskGeneratorClient.Parameters());
    }

    public static void main(final String[] args) throws Exception {
        final String argString =
                "--baseDataUrl http://renderer-dev.int.janelia.org:8080/render-ws/v1 " +
                "--owner hess " +
                "--project wafer_52c " +
                "--stack v1_acquire_slab_001 " +
                "--targetStack v1_acquire_slab_001_test_mask " +
                "--rootDirectory /Users/trautmane/Desktop/masks " +
                "--maxRetainedIntensity 110 " +
                "--z 1249";

        final String[] effectiveArgs = argString.split(" ");

        final SubstrateMaskGeneratorClient.Parameters parameters = new SubstrateMaskGeneratorClient.Parameters();
        parameters.parse(effectiveArgs);

        final SubstrateMaskGeneratorClient client = new SubstrateMaskGeneratorClient(parameters);

        final RenderDataClient dataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                 parameters.renderWeb.owner,
                                                                 parameters.renderWeb.project);

        final TileSpec tileSpec = dataClient.getTile(parameters.stack,
                                                     "001_000005_086_20220407_224815.1249.0");

        client.generateMask(tileSpec, parameters.getBaseMaskDirectoryPath());
    }
}
