package org.janelia.render.service.model.stack;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compound identifier for a stack.
 *
 * @author Eric Trautman
 */
public class StackId implements Comparable<StackId>, Serializable {

    public static final String TILE_COLLECTION_SUFFIX = "tile";
    public static final String TRANSFORM_COLLECTION_SUFFIX = "transform";

    private String owner;
    private String project;
    private String stack;

    private transient String scopePrefix;

    public StackId(String owner,
                   String project,
                   String stack)
            throws IllegalArgumentException {

        validateValue("owner", VALID_NAME, owner);
        validateValue("project", VALID_NAME, project);
        validateValue("stack", VALID_NAME, stack);

        setScopePrefix(owner, project, stack);

        this.owner = owner;
        this.project = project;
        this.stack = stack;
    }

    public String getOwner() {
        return owner;
    }

    public String getProject() {
        return project;
    }

    public String getStack() {
        return stack;
    }

    public String getScopePrefix() {
        if (scopePrefix == null) {
            setScopePrefix(owner, project, stack);
        }
        return scopePrefix;
    }

    @Override
    public String toString() {
        return "StackId{" +
               "owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               ", stack='" + stack + '\'' +
               '}';
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public int compareTo(StackId that) {
        int v = this.owner.compareTo(that.owner);
        if (v == 0) {
            v = this.project.compareTo(that.project);
            if (v == 0) {
                v = this.stack.compareTo(that.stack);
            }
        }
        return v;
    }

    public String getTileCollectionName() {
        return getCollectionName(TILE_COLLECTION_SUFFIX);
    }

    public String getTransformCollectionName() {
        return getCollectionName(TRANSFORM_COLLECTION_SUFFIX);
    }

    private void validateValue(String context,
                               Pattern pattern,
                               String value)
            throws IllegalArgumentException {

        final Matcher m = pattern.matcher(value);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " '" + value + "' specified");
        }
    }

    private void setScopePrefix(String owner,
                                String project,
                                String stack) {

        scopePrefix = owner + FIELD_SEPARATOR + project + FIELD_SEPARATOR + stack + FIELD_SEPARATOR;

        if (scopePrefix.length() > MAX_SCOPE_PREFIX_LENGTH) {
            throw new IllegalArgumentException("scope prefix '" + scopePrefix + "' must be less than " +
                                               MAX_SCOPE_PREFIX_LENGTH + " characters therefore the length of " +
                                               "the owner, project, and/or stack names needs to be reduced");
        }
    }

    private String getCollectionName(final String suffix) {
        return getScopePrefix() + suffix;
    }

    // use consecutive underscores to separate fields within a scoped name
    private static final String FIELD_SEPARATOR = "__";

    // valid names are alphanumeric with underscores but no consecutive underscores
    private static final Pattern VALID_NAME = Pattern.compile("([A-Za-z0-9]+(_[A-Za-z0-9])?)++");

    // From http://docs.mongodb.org/manual/reference/limits/
    //   mongodb namespace limit is 123
    //   subtract 6 characters for the database name: "render"
    //   subtract 9 characters for longest collection type: "transform"
    private static final int MAX_SCOPE_PREFIX_LENGTH = 123 - 6 - 9;
}
