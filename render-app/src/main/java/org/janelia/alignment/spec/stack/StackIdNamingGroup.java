package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * Specifies naming patterns for a group of stacks produced by a common processing step
 * (e.g. alignment or intensity correction).
 *
 * @author Eric Trautman
 */
public class StackIdNamingGroup
        implements Serializable {

    private static final String MATCH_ALL = ".*";

    /** All stacks in the group have a project name that matches this pattern (specify null to match all projects). */
    private final String projectPattern;
    /** All stacks in the group have a stack name that matches this pattern (specify null to match all stacks). */
    private final String stackPattern;

    /** No-arg constructor required for JSON deserialization. */
    @SuppressWarnings("unused")
    private StackIdNamingGroup() {
        this(null, null);
    }

    /** Value constructor. */
    public StackIdNamingGroup(final String projectPattern,
                              final String stackPattern) {
        this.projectPattern = projectPattern;
        this.stackPattern = stackPattern;
    }

    public boolean hasProjectPattern() {
        return (projectPattern != null) && (! projectPattern.isBlank());
    }

    public Pattern projectPattern() {
        return Pattern.compile(hasProjectPattern() ? projectPattern : MATCH_ALL);
    }

    public boolean hasStackPattern() {
        return (stackPattern != null) && (! stackPattern.isBlank());
    }

    public Pattern stackPattern() {
        return Pattern.compile(hasStackPattern() ? stackPattern : MATCH_ALL);
    }

 }
