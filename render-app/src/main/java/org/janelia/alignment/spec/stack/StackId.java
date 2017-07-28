package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.CollectionNameUtil;

/**
 * Compound identifier for a stack.
 *
 * @author Eric Trautman
 */
public class StackId implements Comparable<StackId>, Serializable {

    public static final String SECTION_COLLECTION_SUFFIX = "section";
    public static final String TILE_COLLECTION_SUFFIX = "tile";
    public static final String TRANSFORM_COLLECTION_SUFFIX = "transform";

    private final String owner;
    private final String project;
    private final String stack;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackId() {
        this.owner = null;
        this.project = null;
        this.stack = null;
    }

    public StackId(final String owner,
                   final String project,
                   final String stack)
            throws IllegalArgumentException {

        COLLECTION_NAME_UTIL.validateValue("owner", owner);
        COLLECTION_NAME_UTIL.validateValue("project", project);
        COLLECTION_NAME_UTIL.validateValue("stack", stack);

        this.owner = owner;
        this.project = project;
        this.stack = stack;

        // validate length of longest collection name
        getTransformCollectionName();
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

    @Override
    public String toString() {
        return "stack with owner '" + owner + "', project '" + project + "', and name '" + stack + "'";
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public int compareTo(final StackId that) {
        int v = this.owner.compareTo(that.owner);
        if (v == 0) {
            v = this.project.compareTo(that.project);
            if (v == 0) {
                v = this.stack.compareTo(that.stack);
            }
        }
        return v;
    }

    @JsonIgnore
    public String getSectionCollectionName() {
        return getCollectionName(SECTION_COLLECTION_SUFFIX);
    }

    @JsonIgnore
    public String getTileCollectionName() {
        return getCollectionName(TILE_COLLECTION_SUFFIX);
    }

    @JsonIgnore
    public String getTransformCollectionName() {
        return getCollectionName(TRANSFORM_COLLECTION_SUFFIX);
    }

    private String getCollectionName(final String suffix) {
        return COLLECTION_NAME_UTIL.getName(owner, project, stack, suffix);
    }

    /**
     * Converts a name string with format <pre> owner::project::stack </pre> to a stack ID.
     *
     * @param  nameString      string with format owner::project::stack where owner and project components are optional.
     * @param  defaultOwner    owner to use if not specified in name string.
     * @param  defaultProject  project to use if not specified in name string.
     *
     * @return stack ID with components parsed from the specified name string.
     *
     * @throws IllegalArgumentException
     *   if the parsed stack ID is missing components or is otherwise invalid.
     */
    public static StackId fromNameString(final String nameString,
                                         final String defaultOwner,
                                         final String defaultProject)
            throws IllegalArgumentException {
        final String[] names = nameString.split("::");
        String owner = defaultOwner;
        String project = defaultProject;
        String stack = null;
        if (names.length == 1) {
            stack = names[0];
        } else if (names.length == 2) {
            project = names[0];
            stack = names[1];
        } else if (names.length == 3) {
            owner = names[0];
            project = names[1];
            stack = names[2];
        }
        return new StackId(owner, project, stack);
    }

    private static final JsonUtils.Helper<StackId> JSON_HELPER =
            new JsonUtils.Helper<>(StackId.class);

    private static final CollectionNameUtil COLLECTION_NAME_UTIL = new CollectionNameUtil("render");
}
