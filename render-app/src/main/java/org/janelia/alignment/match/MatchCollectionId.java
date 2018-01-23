package org.janelia.alignment.match;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;

import java.io.Serializable;

import org.janelia.alignment.util.CollectionNameUtil;

/**
 * Compound identifier for a match collection.
 *
 * @author Eric Trautman
 */
public class MatchCollectionId
        implements Comparable<MatchCollectionId>, Serializable {

    private final String owner;
    private final String name;

    private transient String dbCollectionName;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private MatchCollectionId() {
        this.owner = null;
        this.name = null;
    }

    public MatchCollectionId(final String owner,
                             final String name)
            throws IllegalArgumentException {

        COLLECTION_NAME_UTIL.validateValue("owner", owner);
        COLLECTION_NAME_UTIL.validateValue("name", name);

        this.owner = owner;
        this.name = name;
        setDbCollectionName();
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    @JsonIgnore
    public String getDbCollectionName() throws IllegalArgumentException {
        if (dbCollectionName == null) {
            setDbCollectionName();
        }
        return dbCollectionName;
    }

    @Override
    public String toString() {
        return "match collection with owner '" + owner + "', and name '" + name + "'";
    }

    @Override
    public boolean equals(final Object o) {
        final boolean result;
        if (this == o) {
            result = true;
        } else if (o instanceof MatchCollectionId) {
            final MatchCollectionId that = (MatchCollectionId) o;
            result = this.owner.equals(that.owner) &&
                     this.name.equals(that.name);
        } else {
            result = false;
        }
        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(owner, name);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public int compareTo(final MatchCollectionId that) {
        int v = this.owner.compareTo(that.owner);
        if (v == 0) {
            v = this.name.compareTo(that.name);
        }
        return v;
    }

    private void setDbCollectionName() throws IllegalArgumentException {
        dbCollectionName = COLLECTION_NAME_UTIL.getName(owner, name);
    }

    public static MatchCollectionId fromDbCollectionName(final String dbCollectionName)
            throws IllegalArgumentException {

        final String[] fields = COLLECTION_NAME_UTIL.getFields(dbCollectionName);

        if (fields.length != 2) {
            throw new IllegalArgumentException("invalid match collection name '" + dbCollectionName + "'");
        }

        return new MatchCollectionId(fields[0], fields[1]);
    }

    private static final CollectionNameUtil COLLECTION_NAME_UTIL = new CollectionNameUtil("match");
}
