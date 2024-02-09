package org.janelia.render.client.spark.pipeline;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link AlignmentPipelineClient} class.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new AlignmentPipelineClient.Parameters());
    }

}
