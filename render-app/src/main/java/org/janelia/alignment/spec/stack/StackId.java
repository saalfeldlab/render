package org.janelia.alignment.spec.stack;

import java.io.Serializable;

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

    public String getSectionCollectionName() {
        return getCollectionName(SECTION_COLLECTION_SUFFIX);
    }

    public String getTileCollectionName() {
        return getCollectionName(TILE_COLLECTION_SUFFIX);
    }

    public String getTransformCollectionName() {
        return getCollectionName(TRANSFORM_COLLECTION_SUFFIX);
    }

    private String getCollectionName(final String suffix) {
        return COLLECTION_NAME_UTIL.getName(owner, project, stack, suffix);
    }

    private static final CollectionNameUtil COLLECTION_NAME_UTIL = new CollectionNameUtil("render");
}
