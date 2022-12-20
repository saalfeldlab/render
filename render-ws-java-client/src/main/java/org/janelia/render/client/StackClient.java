package org.janelia.render.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

/**
 * Java client for managing stack meta data.
 *
 * @author Eric Trautman
 */
public class StackClient {

    public enum Action { CREATE, CLONE, RENAME, SET_STATE, DELETE }

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--action",
                description = "Management action to perform",
                required = true)
        public Action action;

        @Parameter(
                names = "--stackState",
                description = "New state for stack"
        )
        public StackState stackState;

        @Parameter(
                names = "--versionNotes",
                description = "Notes about the version being created"
        )
        public String versionNotes;

        @Parameter(
                names = "--cycleNumber",
                description = "Processing cycle number"
        )
        public Integer cycleNumber;

        @Parameter(
                names = "--cycleStepNumber",
                description = "Processing cycle step number"
        )
        public Integer cycleStepNumber;

        @Parameter(
                names = "--stackResolutionX",
                description = "X resoution (in nanometers) for the stack"
        )
        public Double stackResolutionX;

        @Parameter(
                names = "--stackResolutionY",
                description = "Y resoution (in nanometers) for the stack"
        )
        public Double stackResolutionY;

        @Parameter(
                names = "--stackResolutionZ",
                description = "Z resoution (in nanometers) for the stack"
        )
        public Double stackResolutionZ;

        @Parameter(
                names = "--materializedBoxRootPath",
                description = "Root path for materialized boxes"
        )
        public String materializedBoxRootPath;

        @Parameter(
                names = "--alignmentQuality",
                description = "Metric for aligned stacks"
        )
        public Double alignmentQuality;

        @Parameter(
                names = "--defaultChannelName",
                description = "Default channel to render (for multi-channel stacks)"
        )
        public String defaultChannelName;

        @Parameter(
                names = "--cloneResultProject",
                description = "Name of project for stack created by clone operation (default is to use source project)"
        )
        public String cloneResultProject;

        @Parameter(
                names = "--cloneResultStack",
                description = "Name of stack created by clone operation"
        )
        public String cloneResultStack;

        @Parameter(
                names = "--renamedOwner",
                description = "Name of renamed stack owner (default is to use source owner)"
        )
        public String renamedOwner;

        @Parameter(
                names = "--renamedProject",
                description = "Name of renamed stack project (default is to use source project)"
        )
        public String renamedProject;

        @Parameter(
                names = "--renamedStack",
                description = "Name of renamed stack"
        )
        public String renamedStack;

        @Parameter(
                names = "--sectionId",
                description = "The sectionId to delete"
        )
        public String sectionId;

        @Parameter(
                names = "--skipSharedTransformClone",
                description = "Only clone tiles, skipping clone of shared transforms (default is false)",
                arity = 0)
        public Boolean skipSharedTransformClone;

        @Parameter(
                names = "--zValues",
                description = "Z values for filtering",
                variableArity = true)
        public List<String> zValues;

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
                } else if (Action.RENAME.equals(parameters.action)) {
                    client.renameStack();
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

    private final Parameters parameters;

    private final String stack;
    private final RenderDataClient renderDataClient;

    private StackClient(final Parameters parameters) {

        this.parameters = parameters;
        this.stack = parameters.stack;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void createStackVersion()
            throws Exception {

        logMetaData("createStackVersion: before save");

        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           parameters.versionNotes,
                                                           parameters.cycleNumber,
                                                           parameters.cycleStepNumber,
                                                           parameters.stackResolutionX,
                                                           parameters.stackResolutionY,
                                                           parameters.stackResolutionZ,
                                                           parameters.materializedBoxRootPath,
                                                           null,
                                                           parameters.alignmentQuality,
                                                           parameters.defaultChannelName);

        renderDataClient.saveStackVersion(stack, stackVersion);

        logMetaData("createStackVersion: after save");
    }

    private void cloneStackVersion()
            throws Exception {

        if (parameters.cloneResultStack == null) {
            throw new IllegalArgumentException("missing --cloneResultStack value");
        }

        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           parameters.versionNotes,
                                                           parameters.cycleNumber,
                                                           parameters.cycleStepNumber,
                                                           parameters.stackResolutionX,
                                                           parameters.stackResolutionY,
                                                           parameters.stackResolutionZ,
                                                           parameters.materializedBoxRootPath,
                                                           null);

        List<Double> zValues = null;
        if (parameters.zValues != null) {
            zValues = new ArrayList<>(parameters.zValues.size());
            for (final String zString : parameters.zValues) {
                zValues.add(new Double(zString));
            }
        }

        renderDataClient.cloneStackVersion(stack,
                                           parameters.cloneResultProject,
                                           parameters.cloneResultStack,
                                           stackVersion,
                                           parameters.skipSharedTransformClone,
                                           zValues);

        logMetaData("cloneStackVersion: after clone", renderDataClient, parameters.cloneResultStack);
    }

    private void renameStack()
            throws Exception {

        if (parameters.renamedStack == null) {
            throw new IllegalArgumentException("missing --renamedStack value");
        }

        final String toOwner = parameters.renamedOwner == null ?
                               parameters.renderWeb.owner : parameters.renamedOwner;
        final String toProject = parameters.renamedProject == null ?
                                 parameters.renderWeb.project : parameters.renamedProject;

        final StackId toStackId = new StackId(toOwner, toProject, parameters.renamedStack);

        renderDataClient.renameStack(stack, toStackId);

        final RenderDataClient renamedDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                        toOwner,
                                                                        toProject);

        logMetaData("renameStack: after rename", renamedDataClient, parameters.renamedStack);
    }

    public void setStackState()
            throws Exception {

        if (parameters.stackState == null) {
            throw new IllegalArgumentException("missing --stackState value");
        }

        logMetaData("setStackState: before update");

        renderDataClient.setStackState(stack, parameters.stackState);

        logMetaData("setStackState: after update");
    }

    private void deleteStack()
            throws Exception {

        logMetaData("deleteStack: before removal");

        if (parameters.zValues == null) {
            if (parameters.sectionId == null) {
                renderDataClient.deleteStack(stack, null);
            } else {
                renderDataClient.deleteStackSection(stack, parameters.sectionId);
            }
        } else {
            Double z;
            for (final String zString : parameters.zValues) {
                z = new Double(zString);
                renderDataClient.deleteStack(stack, z);
            }
            if (parameters.sectionId != null) {
                renderDataClient.deleteStackSection(stack, parameters.sectionId);
            }
        }
    }

    private void logMetaData(final String context) {
        logMetaData(context, renderDataClient, stack);
    }

    private void logMetaData(final String context,
                             final RenderDataClient dataClient,
                             final String stackName) {
        try {
            final StackMetaData stackMetaData = dataClient.getStackMetaData(stackName);
            LOG.info("{}, stackMetaData={}", context, stackMetaData);
        } catch (final IOException e) {
            LOG.info("{}, no meta data returned", context);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackClient.class);
}
