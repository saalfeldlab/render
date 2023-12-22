package org.janelia.render.client.spark.pipeline;

import java.io.IOException;

import org.apache.spark.api.java.JavaSparkContext;

/**
 * Anything that can be run as part of a Spark alignment pipeline.
 *
 * @author Eric Trautman
 */
public interface AlignmentPipelineStep {

    /**
     * Validates the specified pipeline parameters are sufficient for this step.
     *
     * @param  pipelineParameters  parameters to validate.
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid or missing.
     */
    void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException;

    /**
     * Runs the pipeline step.
     *
     * @param  sparkContext        spark context for the run.
     * @param  pipelineParameters  validated parameters for entire pipeline
     *                             (each step implementation knows how to extract its own parameters).
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid.
     *
     * @throws IOException
     *   if the run fails.
     */
    void runPipelineStep(final JavaSparkContext sparkContext,
                         final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException;

}
