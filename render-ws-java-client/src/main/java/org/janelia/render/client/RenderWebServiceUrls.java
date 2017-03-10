package org.janelia.render.client;

import org.janelia.alignment.spec.stack.StackMetaData.StackState;

/**
 * Utility for building render web service URLs.
 *
 * @author Eric Trautman
 */
public class RenderWebServiceUrls {

    private final String baseDataUrl;
    private final String owner;
    private final String project;
    private final String matchCollection;

    public RenderWebServiceUrls(final String baseDataUrl,
                                final String owner,
                                final String projectOrMatchCollection) {
        this(baseDataUrl, owner, projectOrMatchCollection, projectOrMatchCollection); // hack!
    }

    public RenderWebServiceUrls(final String baseDataUrl,
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

    public String getMatchCollectionUrlString() {
        return getOwnerUrlString() + "/matchCollection/" + matchCollection;
    }

    public String getMatchesUrlString() {
        return getMatchCollectionUrlString() + "/matches";
    }

    public String getMatchesWithPGroupIdUrlString(final String pGroupId) {
        return getMatchCollectionUrlString() + "/pGroup/" + pGroupId + "/matches";
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
                                               final double scale) {
        return getZUrlString(stack, z) +
               "/box/" + x + ',' + y + ',' + width + ',' + height + ',' + scale + "/render-parameters";
    }

    @Override
    public String toString() {
        return "{baseDataUrl='" + baseDataUrl + '\'' +
               ", owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               '}';
    }

}
