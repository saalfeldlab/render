package org.janelia.alignment.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for working with MongoDB collection names with embedded fields.
 *
 * @author Eric Trautman
 */
public class CollectionNameUtil {

    private final String databaseName;
    private final String fieldSeparator;
    private final Pattern fieldSeparatorPattern;
    private final Pattern validationPattern;
    private final int maxCollectionNameLength;

    public CollectionNameUtil(final String databaseName) {

        this.databaseName = databaseName;

        // use consecutive underscores to separate fields within a scoped name
        this.fieldSeparator = "__";

        this.fieldSeparatorPattern = Pattern.compile(this.fieldSeparator);

        // valid names are alphanumeric with underscores but no consecutive underscores
        this.validationPattern = Pattern.compile("[A-Za-z0-9]++(_([A-Za-z0-9])++)*+");

        // From https://docs.mongodb.org/manual/reference/limits/#namespaces
        //   The maximum length of the collection namespace, which includes the database name,
        //   the dot (.) separator, and the collection name (i.e. <database>.<collection>), is 120 bytes.
        this.maxCollectionNameLength = 120 - databaseName.length() - 1 - LONGEST_INDEX_NAME.length();
    }

    public void validateValue(final String context,
                              final String value)
            throws IllegalArgumentException {

        final Matcher m = validationPattern.matcher(value);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " '" + value + "' specified, " +
                                               "names may only contain alphanumeric or underscore '_' characters " +
                                               "and may not contain consecutive underscores '_'");
        }
    }

    public String getName(final String... fields) {

//        final String name = String.join(fieldSeparator, fields);
        final StringBuilder name = new StringBuilder(maxCollectionNameLength);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                name.append(fieldSeparator);
            }
            name.append(fields[i]);
        }

        if (name.length() > maxCollectionNameLength) {
            throw new IllegalArgumentException(databaseName + " collection name '" + name +
                                               "' must be less than " + maxCollectionNameLength +
                                               " characters, therefore the length of one or more " +
                                               "of the collection fields should be reduced");
        }

        return name.toString();
    }

    public String[] getFields(final String name) {
        return fieldSeparatorPattern.split(name);
    }

    /**
     * Database index names factor into namespace length constraints.
     * Indexes are currently named with single letters to reduce impact on the namespace.
     */
    private static final String LONGEST_INDEX_NAME = ".A";
}
