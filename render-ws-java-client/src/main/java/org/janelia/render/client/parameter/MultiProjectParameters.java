package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            description = "Project for all stacks",
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

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public MatchCollectionId getMatchCollectionIdForStack(final StackId stackId) {
        return matchCollection == null ?
               stackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject) :
               new MatchCollectionId(owner, matchCollection);
    }

    public List<StackWithZValues> buildListOfStackWithBatchedZ()
            throws IOException, IllegalArgumentException {
        return stackIdWithZ.buildListOfStackWithBatchedZ(this.getDataClient());
    }

    public List<StackWithZValues> buildListOfStackWithBatchedZ(final int zValuesPerBatch)
            throws IOException, IllegalArgumentException {
        return stackIdWithZ.buildListOfStackWithBatchedZ(this.getDataClient(), zValuesPerBatch);
    }

    public List<StackWithZValues> buildListOfStackWithAllZ()
            throws IOException, IllegalArgumentException {
        return stackIdWithZ.buildListOfStackWithAllZ(this.getDataClient());
    }

    public List<StackMFOVWithZValues> buildListOfStackMFOVWithAllZ(final String multiFieldOfViewId)
            throws IOException, IllegalArgumentException {

        final List<StackMFOVWithZValues> stackMFOVWithZValuesList = new ArrayList<>();

        final RenderDataClient defaultRenderClient = getDataClient();
        final List<StackWithZValues> stackWithZValuesList = buildListOfStackWithAllZ();
        for (final StackWithZValues stackWithZValues : stackWithZValuesList) {
            final StackId stackId = stackWithZValues.getStackId();
            final RenderDataClient dataClient = defaultRenderClient.buildClient(stackId.getOwner(),
                                                                                stackId.getProject());
            final List<String> mFOVIdList = Utilities.getMFOVNames(dataClient,
                                                                   stackId.getStack(),
                                                                   stackWithZValues.getFirstZ());

            LOG.info("buildListOfStackMFOVWithAllZ: found {} MFOVs in {}", mFOVIdList.size(), stackWithZValues);

            for (final String mFOVId : mFOVIdList) {
                if ((multiFieldOfViewId == null) || multiFieldOfViewId.equals(mFOVId)) {
                    stackMFOVWithZValuesList.add(new StackMFOVWithZValues(stackWithZValues, mFOVId));
                }
            }
        }

        return stackMFOVWithZValuesList;
    }

    public void setNamingGroup(final StackIdNamingGroup namingGroup) {
        if ((namingGroup != null) && (this.stackIdWithZ != null)) {
            this.stackIdWithZ.setNamingGroup(namingGroup);
        }
    }

    private synchronized void buildDataClient() {
        if (dataClient == null) {
            dataClient = new RenderDataClient(baseDataUrl, owner, project);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiProjectParameters.class);

}