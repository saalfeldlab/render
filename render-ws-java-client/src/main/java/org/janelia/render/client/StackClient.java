package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState;

/**
 * Java client for managing stack meta data.
 *
 * @author Eric Trautman
 */
public class StackClient {

    public enum Action { CREATE, CLONE, SET_STATE, DELETE }

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--action", description = "CREATE, CLONE, SET_STATE, or DELETE", required = true)
        private Action action;

        @Parameter(names = "--stackState", description = "LOADING, COMPLETE, or OFFLINE", required = false)
        private StackState stackState;

        @Parameter(names = "--versionNotes", description = "Notes about the version being created", required = false)
        private String versionNotes;

        @Parameter(names = "--cycleNumber", description = "Processing cycle number", required = false)
        private Integer cycleNumber;

        @Parameter(names = "--cycleStepNumber", description = "Processing cycle step number", required = false)
        private Integer cycleStepNumber;

        @Parameter(names = "--stackResolutionX", description = "X resoution (in nanometers) for the stack", required = false)
        private Double stackResolutionX;

        @Parameter(names = "--stackResolutionY", description = "Y resoution (in nanometers) for the stack", required = false)
        private Double stackResolutionY;

        @Parameter(names = "--stackResolutionZ", description = "Z resoution (in nanometers) for the stack", required = false)
        private Double stackResolutionZ;

        @Parameter(names = "--materializedBoxRootPath", description = "Root path for materialized boxes", required = false)
        private String materializedBoxRootPath;

        @Parameter(names = "--cloneResultProject", description = "Name of project for stack created by clone operation (default is to use source project)", required = false)
        private String cloneResultProject;

        @Parameter(names = "--cloneResultStack", description = "Name of stack created by clone operation", required = false)
        private String cloneResultStack;

        @Parameter(names = "--zValues", description = "Z values for filtering", required = false, variableArity = true)
        private List<String> zValues;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final StackClient client = new StackClient(parameters);

                if (Action.CREATE.equals(parameters.action)) {
                    client.createStackVersion();
                } else if (Action.CLONE.equals(parameters.action)) {
                    client.cloneStackVersion();
                } else if (Action.SET_STATE.equals(parameters.action)) {
                    client.setStackState();
                } else if (Action.DELETE.equals(parameters.action)) {
                    client.deleteStack();
                } else {
                    throw new IllegalArgumentException("unknown action '" + parameters.action + "' specified");
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters params;

    private final String stack;
    private final RenderDataClient renderDataClient;

    public StackClient(final Parameters params) {

        this.params = params;
        this.stack = params.stack;
        this.renderDataClient = params.getClient();
    }

    public void createStackVersion()
            throws Exception {

        logMetaData("createStackVersion: before save");

        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           params.versionNotes,
                                                           params.cycleNumber,
                                                           params.cycleStepNumber,
                                                           params.stackResolutionX,
                                                           params.stackResolutionY,
                                                           params.stackResolutionZ,
                                                           params.materializedBoxRootPath,
                                                           null);

        renderDataClient.saveStackVersion(stack, stackVersion);

        logMetaData("createStackVersion: after save");
    }

    public void cloneStackVersion()
            throws Exception {

        if (params.cloneResultStack == null) {
            throw new IllegalArgumentException("missing --cloneResultStack value");
        }

        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           params.versionNotes,
                                                           params.cycleNumber,
                                                           params.cycleStepNumber,
                                                           params.stackResolutionX,
                                                           params.stackResolutionY,
                                                           params.stackResolutionZ,
                                                           params.materializedBoxRootPath,
                                                           null);

        List<Double> zValues = null;
        if (params.zValues != null) {
            zValues = new ArrayList<>(params.zValues.size());
            for (final String zString : params.zValues) {
                zValues.add(new Double(zString));
            }
        }

        renderDataClient.cloneStackVersion(stack, params.cloneResultProject, params.cloneResultStack, stackVersion, zValues);

        logMetaData("cloneStackVersion: after clone", params.cloneResultStack);
    }

    public void setStackState()
            throws Exception {

        if (params.stackState == null) {
            throw new IllegalArgumentException("missing --stackState value");
        }

        logMetaData("setStackState: before update");

        renderDataClient.setStackState(stack, params.stackState);

        logMetaData("setStackState: after update");
    }

    public void deleteStack()
            throws Exception {

        logMetaData("deleteStack: before removal");

        if (params.zValues == null) {
            renderDataClient.deleteStack(stack, null);
        } else {
            Double z;
            for (final String zString : params.zValues) {
                z = new Double(zString);
                renderDataClient.deleteStack(stack, z);
            }
        }
    }

    private void logMetaData(final String context) {
        logMetaData(context, stack);
    }

    private void logMetaData(final String context,
                             final String stackName) {
        try {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stackName);
            LOG.info("{}, stackMetaData={}", context, stackMetaData);
        } catch (final IOException e) {
            LOG.info("{}, no meta data returned", context);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackClient.class);
}
