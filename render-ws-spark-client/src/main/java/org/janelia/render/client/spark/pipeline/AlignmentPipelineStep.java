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
     * This method quietly completes (doing nothing) if the parameters are valid
     * but will throw an exception it finds a problem.
     *
     * @param  pipelineParameters  parameters to validate.
     *                             Parameters are simply read and should not be mutated during validation.
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
