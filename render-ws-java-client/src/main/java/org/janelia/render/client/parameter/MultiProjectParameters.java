package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;
import org.janelia.alignment.multisem.MultiSemUtilities;
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

    public void setBaseDataUrl(final String baseDataUrl) {
        this.baseDataUrl = baseDataUrl;
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
            final List<String> mFOVIdList = getSortedMFOVNamesForZValues(dataClient,
                                                                         stackId.getStack(),
                                                                         stackWithZValues.getzValues());

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

    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private synchronized void buildDataClient() {
        if (dataClient == null) {
            dataClient = new RenderDataClient(baseDataUrl, owner, project);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiProjectParameters.class);

    private static final JsonUtils.Helper<MultiProjectParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MultiProjectParameters.class);

    /**
     * @return list of distinct sorted MFOV names for the specified list of stack z-layer values.
     */
    public static List<String> getSortedMFOVNamesForZValues(final RenderDataClient renderDataClient,
                                                            final String stack,
                                                            final List<Double> zValues)
            throws IOException {
        final Set<String> mFOVNames = new HashSet<>();
        for (final Double z : zValues) {
            mFOVNames.addAll(getSortedMFOVNamesForOneLayer(renderDataClient, stack, z));
        }
        return mFOVNames.stream().sorted().collect(Collectors.toList());
    }

    /**
     * @return list of distinct sorted MFOV names for the specified stack z-layer.
     */
    public static List<String> getSortedMFOVNamesForOneLayer(final RenderDataClient renderDataClient,
                                                             final String stack,
                                                             final Double z)
            throws IOException {
        return renderDataClient.getTileBounds(stack, z)
                .stream()
                .map(tileBounds -> MultiSemUtilities.getMagcMfovForTileId(tileBounds.getTileId()))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public static MultiProjectParameters singleStackInstance(final String baseDataUrl,
                                                             final StackId stackId) {
        final MultiProjectParameters multiProjectParameters = new MultiProjectParameters();
        multiProjectParameters.baseDataUrl = baseDataUrl;
        multiProjectParameters.owner = stackId.getOwner();
        multiProjectParameters.project = stackId.getProject();
        multiProjectParameters.stackIdWithZ.stackNames = Collections.singletonList(stackId.getStack());
        return multiProjectParameters;
    }
}