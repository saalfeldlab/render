package org.janelia.render.client.spark.pipeline;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.junit.jupiter.api.Test;

import static org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId.DERIVE_TILE_MATCHES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link AlignmentPipelineParameters} class.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineParametersTest {

    @Test
    public void testAffineBlockSolverSetup()
            throws IOException {
        final AlignmentPipelineParameters pipelineParameters = loadTestParameters();
        final AffineBlockSolverSetup affineBlockSolverSetup = pipelineParameters.getAffineBlockSolverSetup();
        assertNotNull(affineBlockSolverSetup, "affineBlockSolverSetup is null");

        assertEquals("_align", affineBlockSolverSetup.targetStack.stackSuffix,
                     "incorrect affineBlockSolverSetup.targetStack.stackSuffix value parsed");

        assertTrue(affineBlockSolverSetup.targetStack.completeStack,
                   "incorrect affineBlockSolverSetup.targetStack.completeTargetStack value parsed");
    }

    @Test
    public void testBuildStepClients()
            throws IOException {
        final AlignmentPipelineParameters pipelineParameters = loadTestParameters();
        final List<AlignmentPipelineStep> stepClients = pipelineParameters.buildStepClients();
        
        assertNotNull(stepClients, "stepClients is null");
        assertEquals(7, stepClients.size(), "incorrect number of stepClients");

        final AlignmentPipelineStep stepClient = stepClients.getFirst();
        assertEquals(AlignmentPipelineStepId.GENERATE_MIPMAPS, stepClient.getDefaultStepId(),
                     "first stepClient has incorrect defaultStepId");
    }

    @Test
    public void testLoadParametersFromUrl()
            throws IOException {
        final String commitJsonUrlString =
                "https://raw.githubusercontent.com/saalfeldlab/render/748e99806b3be06d5c7ac06a538698f7c523cb26";
        final String pathJsonUrlString =
                "/render-ws-spark-client/src/main/resources/multisem/wafer_60/pipeline_json/01_match/pipe.01.match.json";
        final URL jsonUrl = new URL(commitJsonUrlString + pathJsonUrlString);

        final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        final AlignmentPipelineParameters pipelineParameters =
                AlignmentPipelineParameters.fromJsonUrl(jsonUrl,
                                                        baseDataUrl);
        assertNotNull(pipelineParameters, "deserialized parameters are null");

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(null);
        assertEquals(baseDataUrl, multiProject.getBaseDataUrl(), "");

        final List<AlignmentPipelineStepId> pipelineSteps = pipelineParameters.getPipelineSteps();
        assertNotNull(pipelineSteps, "pipelineSteps are null");
        assertEquals(1, pipelineSteps.size(),
                     "incorrect number of pipelineSteps");
        assertEquals(DERIVE_TILE_MATCHES.toString(), pipelineSteps.getFirst().toString(),
                     "incorrect first pipelineStep");
    }

    private AlignmentPipelineParameters loadTestParameters() throws IOException {
        final AlignmentPipelineParameters pipelineParameters =
                AlignmentPipelineParameters.fromJsonFile("src/test/resources/pipeline/msem_alignment_pipeline.json",
                                                         null);
        assertNotNull(pipelineParameters, "deserialized parameters are null");
        return pipelineParameters;
    }
}
