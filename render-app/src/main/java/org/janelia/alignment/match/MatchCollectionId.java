package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        validateValue("owner", VALID_NAME, owner);
        validateValue("name", VALID_NAME, name);

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

    public String getDbCollectionName() throws IllegalArgumentException {
        if (dbCollectionName == null) {
            setDbCollectionName();
        }
        return dbCollectionName;
    }

    @Override
    public String toString() {
        return "{'owner': '" + owner +
               "', 'name': '" + name + "'}";
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

    private void validateValue(final String context,
                               final Pattern pattern,
                               final String value)
            throws IllegalArgumentException {

        final Matcher m = pattern.matcher(value);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " '" + value + "' specified");
        }
    }

    private void setDbCollectionName() throws IllegalArgumentException {

        dbCollectionName = owner + FIELD_SEPARATOR + name;

        if (dbCollectionName.length() > MAX_COLLECTION_NAME_LENGTH) {
            throw new IllegalArgumentException("match db collection name '" + this.dbCollectionName +
                                               "' must be less than " + MAX_COLLECTION_NAME_LENGTH +
                                               " characters therefore the length of the owner and/or " +
                                               "match collection names needs to be reduced");
        }
    }

    public static MatchCollectionId fromDbCollectionName(final String dbCollectionName)
            throws IllegalArgumentException {

        final int separatorIndex = dbCollectionName.indexOf(FIELD_SEPARATOR);
        final int nameIndex = separatorIndex + FIELD_SEPARATOR.length();

        if ((separatorIndex < 1) || (dbCollectionName.length() <= nameIndex)) {
            throw new IllegalArgumentException("invalid match collection name '" + dbCollectionName + "'");
        }

        final String owner = dbCollectionName.substring(0, separatorIndex);
        final String name = dbCollectionName.substring(nameIndex);

        return new MatchCollectionId(owner,name);
    }

    // use consecutive underscores to separate fields within a scoped name
    private static final String FIELD_SEPARATOR = "__";

    // valid names are alphanumeric with underscores but no consecutive underscores
    private static final Pattern VALID_NAME = Pattern.compile("([A-Za-z0-9]+(_[A-Za-z0-9])?)++");

    // From http://docs.mongodb.org/manual/reference/limits/
    //   mongodb namespace limit is 123
    //   subtract 5 characters for the database name: "match"
    private static final int MAX_COLLECTION_NAME_LENGTH = 123 - 5;
}
