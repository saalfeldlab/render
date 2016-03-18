package org.janelia.alignment.match;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * Meta data about a match collection.
 *
 * @author Eric Trautman
 */
public class MatchCollectionMetaData
        implements Comparable<MatchCollectionMetaData>, Serializable {

    private final MatchCollectionId collectionId;
    private final Long pairCount;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private MatchCollectionMetaData() {
        this.collectionId = null;
        this.pairCount = null;
    }

    public MatchCollectionMetaData(final MatchCollectionId collectionId,
                                   final Long pairCount) {
        this.collectionId = collectionId;
        this.pairCount = pairCount;
    }

    public MatchCollectionId getCollectionId() {
        return collectionId;
    }

    public Long getPairCount() {
        return pairCount;
    }

    public String getOwner() {
        String owner = null;
        if (collectionId != null) {
            owner = collectionId.getOwner();
        }
        return owner;
    }

    @SuppressWarnings({"ConstantConditions", "NullableProblems"})
    @Override
    public int compareTo(final MatchCollectionMetaData that) {
        return this.collectionId.compareTo(that.collectionId);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static MatchCollectionMetaData fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<MatchCollectionMetaData> JSON_HELPER =
            new JsonUtils.Helper<>(MatchCollectionMetaData.class);
}
