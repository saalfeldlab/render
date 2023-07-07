package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for identifying projects, stacks, and match collections.
 *
 * @author Eric Trautman
 */
@Parameters
public class MultiProjectParameters
        implements Serializable {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true)
    public String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Owner for all stacks and match collections",
            required = true)
    public String owner;

    @Parameter(
            names = "--project",
            description = "Project for all tiles (or first project if processing a multi-project run)",
            required = true)
    public String project;

    @Parameter(
            names = "--matchCollection",
            description = "Explicit collection in which to store matches (omit to use stack derived names)")
    public String matchCollection;

    @Parameter(
            names = "--deriveMatchCollectionNamesFromProject",
            description = "Indicates that match collection names should be derived from the stack's project " +
                          "instead of using the default approach which derives collection names from the stack's name",
            arity = 0)
    public boolean deriveMatchCollectionNamesFromProject;

    @ParametersDelegate
    public StackIdWithZParameters stackIdWithZ = new StackIdWithZParameters();

    /** Local data client instance that is lazy loaded since client cannot be serialized. */
    private transient RenderDataClient dataClient;

    @JsonIgnore
    public RenderDataClient getDataClient() {
        if (dataClient == null) {
            buildDataClient();
        }
        return dataClient;
    }

    public MultiProjectParameters() {
    }

    public MatchCollectionId getMatchCollectionIdForStack(final StackId stackId) {
        final MatchCollectionId stackMatchCollectionId;
        if (matchCollection == null) {
            final String baseName = deriveMatchCollectionNamesFromProject ? stackId.getProject() : stackId.getStack();
            stackMatchCollectionId = new MatchCollectionId(stackId.getOwner(),
                                                           baseName + "_match");
        } else {
            stackMatchCollectionId = new MatchCollectionId(owner, matchCollection);
        }
        return stackMatchCollectionId;
    }

    public List<StackWithZValues> buildStackWithZValuesList()
            throws IOException, IllegalArgumentException {
        final RenderDataClient renderDataClient = getDataClient();
        final List<StackWithZValues> stackWithZValuesList = stackIdWithZ.getStackWithZList(renderDataClient);
        if (stackWithZValuesList.size() == 0) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }
        return stackWithZValuesList;
    }

    private synchronized void buildDataClient() {
        if (dataClient == null) {
            dataClient = new RenderDataClient(baseDataUrl, owner, project);
        }
    }

}