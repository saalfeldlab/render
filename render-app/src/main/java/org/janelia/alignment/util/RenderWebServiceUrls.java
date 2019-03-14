package org.janelia.alignment.util;

import java.io.Serializable;
import java.net.URISyntaxException;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;

/**
 * Utility for building render web service URLs.
 *
 * @author Eric Trautman
 */
public class RenderWebServiceUrls implements Serializable {

    private final String baseDataUrl;
    private final String owner;
    private final String project;
    private final String matchCollection;

    public RenderWebServiceUrls(final String baseDataUrl,
                                final String owner,
                                final String projectOrMatchCollection) {
        this(baseDataUrl, owner, projectOrMatchCollection, projectOrMatchCollection); // hack!
    }

    private RenderWebServiceUrls(final String baseDataUrl,
                                 final String owner,
                                 final String project,
                                 final String matchCollection) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.project = project;
        this.matchCollection = matchCollection;
    }

    public String getLikelyUniqueIdUrlString() {
        return baseDataUrl + "/likelyUniqueId";
    }

    public String getOwnerUrlString() {
        return baseDataUrl + "/owner/" + owner;
    }

    public String getStackIdsUrlString(final String project) {
        final StringBuilder urlString = new StringBuilder(baseDataUrl).append("/owner/").append(owner);
        if (project != null) {
            urlString.append("/project/").append(project);
        }
        urlString.append("/stackIds");
        return urlString.toString();
    }

    public String getOwnerMatchCollectionsUrlString() {
        return getOwnerUrlString() + "/matchCollections";
    }

    public String getMatchCollectionUrlString() {
        return getOwnerUrlString() + "/matchCollection/" + matchCollection;
    }

    public String getMatchesUrlString() {
        return getMatchCollectionUrlString() + "/matches";
    }

    public String getMatchMultiConsensusGroupIdsUrlString() {
        return getMatchCollectionUrlString() + "/multiConsensusGroupIds";
    }

    public String getMatchMultiConsensusPGroupIdsUrlString() {
        return getMatchCollectionUrlString() + "/multiConsensusPGroupIds";
    }

    public String getMatchesWithPGroupIdUrlString(final String pGroupId) {
        return getMatchCollectionUrlString() + "/pGroup/" + pGroupId + "/matches";
    }

    public String getMatchesOutsideGroupUrlString(final String groupId) {
        return getMatchCollectionUrlString() + "/group/" + groupId + "/matchesOutsideGroup";
    }

    public String getMatchesWithinGroupUrlString(final String groupId) {
        return getMatchCollectionUrlString() + "/group/" + groupId + "/matchesWithinGroup";
    }

    public String getStackUrlString(final String stack) {
        return getOwnerUrlString() + "/project/" + project + "/stack/" + stack;
    }

    public String getZUrlString(final String stack,
                                final double z) {
        return getStackUrlString(stack) + "/z/" + z;
    }

    public String getCloneToUrlString(final String fromStack,
                                      final String toStack) {
        return getStackUrlString(fromStack) + "/cloneTo/" + toStack;
    }

    public String getStackStateUrlString(final String stack,
                                         final StackState stackState) {
        return getStackUrlString(stack) + "/state/" + stackState;
    }

    public String getSectionUrlString(final String stack,
                                      final String sectionId) {
        return getStackUrlString(stack) + "/section/" + sectionId;
    }

    public String getSectionZUrlString(final String stack,
                                       final String sectionId) {
        return getSectionUrlString(stack, sectionId) + "/z";
    }

    public String getTileUrlString(final String stack,
                                   final String tileId) {
        return getStackUrlString(stack) + "/tile/" + tileId;
    }

    public String getBoundsUrlString(final String stack,
                                     final double z) {
        return getZUrlString(stack, z) + "/bounds";
    }

    public String getTileBoundsUrlString(final String stack,
                                         final double z) {
        return getZUrlString(stack, z) + "/tileBounds";
    }

    public String getTileIdsUrlString(final String stack,
                                      final double z) {
        return getZUrlString(stack, z) + "/tileIds";
    }

    public String getTileIdsForCoordinatesUrlString(final String stack,
                                                    final Double z) {
        final String baseUrlString;
        if (z == null) {
            baseUrlString = getStackUrlString(stack);
        } else {
            baseUrlString = getZUrlString(stack, z);
        }
        return baseUrlString + "/tileIdsForCoordinates";
    }

    public String getRenderParametersUrlString(final String stack,
                                               final double x,
                                               final double y,
                                               final double z,
                                               final int width,
                                               final int height,
                                               final double scale,
                                               final String filterListName) {
        final String urlString = getZUrlString(stack, z) +
                                 "/box/" + x + ',' + y + ',' + width + ',' + height + ',' + scale +
                                 "/render-parameters";
        return addParameter("filterListName", filterListName, urlString);
    }

    @Override
    public String toString() {
        return "{baseDataUrl='" + baseDataUrl + '\'' +
               ", owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               '}';
    }

    public static String addParameter(final String parameterName,
                                      final String parameterValue,
                                      final String toUrlString) {
        final String urlStringWithParameter;
        if (parameterValue != null) {
            try {
                final URIBuilder uriBuilder = new URIBuilder(toUrlString);
                uriBuilder.addParameter(parameterName, parameterValue);
                urlStringWithParameter = uriBuilder.toString();
            } catch (final URISyntaxException e) {
                throw new IllegalArgumentException(
                        "failed to add '" + parameterName + "' parameter to '" + toUrlString + "'", e);
            }
        } else {
            urlStringWithParameter = toUrlString;
        }
        return urlStringWithParameter;
    }
}
