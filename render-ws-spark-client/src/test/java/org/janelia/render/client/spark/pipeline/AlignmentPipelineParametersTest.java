package org.janelia.render.client.spark.pipeline;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.junit.Assert;
import org.junit.Test;

import static org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId.DERIVE_TILE_MATCHES;

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
        Assert.assertNotNull("affineBlockSolverSetup is null", affineBlockSolverSetup);

        Assert.assertEquals("incorrect affineBlockSolverSetup.targetStack.stackSuffix value parsed",
                            "_align", affineBlockSolverSetup.targetStack.stackSuffix);

        Assert.assertTrue("incorrect affineBlockSolverSetup.targetStack.completeTargetStack value parsed",
                          affineBlockSolverSetup.targetStack.completeStack);
    }

    @Test
    public void testBuildStepClients()
            throws IOException {
        final AlignmentPipelineParameters pipelineParameters = loadTestParameters();
        final List<AlignmentPipelineStep> stepClients = pipelineParameters.buildStepClients();
        
        Assert.assertNotNull("stepClients is null", stepClients);
        Assert.assertEquals("incorrect number of stepClients", 8, stepClients.size());

        final AlignmentPipelineStep stepClient = stepClients.get(0);
        Assert.assertEquals("first stepClient has incorrect defaultStepId",
                            AlignmentPipelineStepId.GENERATE_MIPMAPS, stepClient.getDefaultStepId());
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
        Assert.assertNotNull("deserialized parameters are null", pipelineParameters);

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(null);
        Assert.assertEquals("", baseDataUrl, multiProject.getBaseDataUrl());

        final List<AlignmentPipelineStepId> pipelineSteps = pipelineParameters.getPipelineSteps();
        Assert.assertNotNull("pipelineSteps are null", pipelineSteps);
        Assert.assertEquals("incorrect number of pipelineSteps",
                            1, pipelineSteps.size());
        Assert.assertEquals("incorrect first pipelineStep",
                            DERIVE_TILE_MATCHES.toString(), pipelineSteps.get(0).toString());
    }

    private AlignmentPipelineParameters loadTestParameters() throws IOException {
        final AlignmentPipelineParameters pipelineParameters =
                AlignmentPipelineParameters.fromJsonFile("src/test/resources/pipeline/msem_alignment_pipeline.json",
                                                         null);
        Assert.assertNotNull("deserialized parameters are null", pipelineParameters);
        return pipelineParameters;
    }
}
