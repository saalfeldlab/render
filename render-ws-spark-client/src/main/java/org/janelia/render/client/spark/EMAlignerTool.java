package org.janelia.render.client.spark;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.ProcessBuilder.Redirect.INHERIT;

/**
 * Java wrapper for running <a href="https://github.com/khaledkhairy/EM_aligner"> EM_aligner </a> tool.
 *
 * <pre>
 *     # Example command line invocation:
 *     /groups/flyTEM/flyTEM/matlab_compiled/bin/run_system_solve_affine_with_constraint_SL.sh \
 *     /tmp/solve_affine_parameters.json
 * </pre>
 *
 * @author Eric Trautman
 */
public class EMAlignerTool
        implements Serializable {

    private final String scriptPath;
    private final File scriptParametersTemplateFile;

    /**
     *
     * @param  scriptFile                    script that invokes Matlab compiled version of EM_aligner.
     * @param  scriptParametersTemplateFile  common template for deriving stack/match specific parameters.
     *
     * @throws IllegalArgumentException
     *   if the specified files are not available.
     */
    public EMAlignerTool(final File scriptFile,
                         final File scriptParametersTemplateFile)
            throws IllegalArgumentException {

        this.scriptPath = scriptFile.getAbsolutePath();
        this.scriptParametersTemplateFile = scriptParametersTemplateFile.getAbsoluteFile();

        if (! scriptFile.canExecute()) {
            throw new IllegalArgumentException(this.scriptPath + " is not executable");
        }

        if (! this.scriptParametersTemplateFile.canRead()) {
            throw new IllegalArgumentException(this.scriptParametersTemplateFile + " is not readable");
        }
    }

    /**
     * Applies the specified parameters to this tool's template to create a run specific parameters file.
     *
     * @param  baseDataUrl        base web service URL for data (e.g. http://host[:port]/render-ws/v1)
     * @param  sourceStack        identifies the source render stack.
     * @param  firstZ             first layer to solve.
     * @param  lastZ              last layer to solve.
     * @param  matchOwner         owner of the point match collection.
     * @param  matchCollection    name of the point match collection.
     * @param  targetStack        identifies target render stack where solution transforms are to be stored.
     * @param  parametersFile     (writeable) file that will contain solver parameters after applying
     *                            parameters specified here to the parameters template file.
     *
     * @throws IOException
     *   if the parameters file cannot be produced.
     */
    public void generateParametersFile(final String baseDataUrl,
                                       final StackId sourceStack,
                                       final Double firstZ,
                                       final Double lastZ,
                                       final String matchOwner,
                                       final String matchCollection,
                                       final Integer zNeighborDistance,
                                       final StackId targetStack,
                                       final File parametersFile)
            throws IOException {

        LOG.info("generateParametersFile: entry");

        // Parameter file format is:
        //
        // {
        //   "first_section": 1,
        //   "last_section": 3,
        //   "solver_options": {
        //     ...
        //     "nbrs": 1
        //     ...
        //     "dir_scratch": "/scratch/trautmane",
        //     ...
        //   },
        //   "source_collection": {
        //     "owner": "flyTEM",
        //     "project": "test_tier_1",
        //     "stack": "0003x0003_000001",
        //     "baseURL": "http://renderer-dev:8080/render-ws/v1"
        //   },
        //   "target_collection": {
        //     "owner": "flyTEM",
        //     "project": "test_tier_1",
        //     "stack": "0003x0003_000001_fine",
        //     "baseURL": "http://renderer-dev:8080/render-ws/v1"
        //   },
        //    "source_point_match_collection": {
        //      "server": "http://renderer-dev:8080/render-ws/v1",
        //      "owner": "flyTEM",
        //      "match_collection": "test_tier_1_0003x0003_000001"
        //    },
        //    "verbose": 1
        //  }

        // NOTE: assumes redundant "service_host" attributes are not needed

        final Map<String, Object> parameters = JsonUtils.MAPPER.readValue(scriptParametersTemplateFile,
                                                                          new TypeReference<Map<String, Object>>(){});

        final Map<String, Object> solverOptions = getNestedMap(parameters, "solver_options");
        final Map<String, Object> sourceCollection = getNestedMap(parameters, "source_collection");
        final Map<String, Object> targetCollection = getNestedMap(parameters, "target_collection");
        final Map<String, Object> sourceMatchCollection = getNestedMap(parameters, "source_point_match_collection");

        setParameter(solverOptions, "nbrs", zNeighborDistance);
        final File scratchDirectory = new File(parametersFile.getParent(),
                                               "solver_scratch_" + targetStack.getStack());
        setParameter(solverOptions, "dir_scratch", scratchDirectory.getAbsolutePath());

        setParameter(parameters, "first_section", firstZ);
        setParameter(parameters, "last_section", lastZ);

        setStackValues(sourceCollection, sourceStack, baseDataUrl);
        setStackValues(targetCollection, targetStack, baseDataUrl);

        setParameter(sourceMatchCollection, "server", baseDataUrl);
        setParameter(sourceMatchCollection, "owner", matchOwner);
        setParameter(sourceMatchCollection, "match_collection", matchCollection);

        JsonUtils.MAPPER.writeValue(parametersFile, parameters);

        LOG.info("generateParametersFile: exit, saved {}", parametersFile.getAbsolutePath());
    }

    /**
     * Runs the solver using the specified parameters.
     *
     * TODO: Consider other serial run options.
     *       There is significant (15-30 seconds?) startup time when launching
     *       Matlab Compiled Runtime processes.  For runs with larger numbers
     *       of split stacks to solve, we may want to add EM_aligner support
     *       for iterating through solutions of multiple stacks.
     *
     * @param  parametersFile     json file with solver parameters.
     *
     * @return code returned from the solver call.
     *
     * @throws IOException
     *   if the solver process cannot be started.
     *
     * @throws InterruptedException
     *   if the solver run gets interrupted.
     */
    public int run(final File parametersFile)
            throws IOException, InterruptedException {

        final ProcessTimer timer = new ProcessTimer();

        final ProcessBuilder processBuilder =
                new ProcessBuilder(scriptPath,
                                   parametersFile.getAbsolutePath()).
                        redirectOutput(INHERIT).
                        redirectError(INHERIT);

        LOG.info("run: running {}", processBuilder.command());

        final Process process = processBuilder.start();

        final int returnCode = process.waitFor();

        if (returnCode != 0) {
            final String errorMessage = "code " + returnCode + " returned from " + processBuilder.command();
            LOG.warn(errorMessage);
        }

        LOG.info("run: exit, elapsedTime={}s", timer.getElapsedSeconds());

        return returnCode;
    }

    /**
     * Allows you to run tool from command line to debug issues.
     *
     * @param  args  args[0] is script file, args[1] is final parameters file (no template used)
     */
    public static void main(final String[] args) {

        if (args.length != 2) {
            throw new IllegalArgumentException("Expected parameters are: scriptFile parametersFile");
        }

        final EMAlignerTool tool = new EMAlignerTool(new File(args[0]), new File(args[1]));
        try {
            tool.run(new File(args[1]));
        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }

    }

    // Helper methods for populating JSON parameters template ...

    private Map<String, Object> getNestedMap(final Map<String, Object> parameters,
                                             final String key) {
        final Map<String, Object> map;
        final Object o = parameters.get(key);
        if (o instanceof Map) {
            //noinspection unchecked
            map = (Map<String, Object>) o;
        } else {
            map = null;
        }
        return map;
    }

    private void setParameter(final Map<String, Object> map,
                              final String key,
                              final Object value) {
        if (map != null) {
            if (value != null) {
                map.put(key, value);
            }
        }
    }

    private void setStackValues(final Map<String, Object> map,
                                final StackId stackId,
                                final String baseDataUrl) {
        if (stackId != null) {
            setParameter(map, "owner", stackId.getOwner());
            setParameter(map, "project", stackId.getProject());
            setParameter(map, "stack", stackId.getStack());
        }
        if (baseDataUrl != null) {
            setParameter(map, "baseURL", baseDataUrl);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(EMAlignerTool.class);
}
