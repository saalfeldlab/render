package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parameters for renaming stacks.
 */
@Parameters
public class StackRenameParameters
        implements Serializable {

    @Parameter(
            names = "--stackOwner",
            description = "Owner of the stacks to rename")
    public String stackOwner;

    @Parameter(
            names = "--sourceNamePattern",
            description = "Regular expression for the names of the stacks to rename " +
                          "(e.g. '(w6.*_gc)')")
    public String sourceNamePattern;

    @Parameter(
            names = "--targetNamePattern",
            description = "Replacement for renamed stacks that can reference capture groups " +
                          "from the sourceNamePattern (e.g. '$1_bc' renames " +
                          "w61_s140_r00_gc to w61_s140_r00_gc_bc)")
    public String targetNamePattern;

    public StackRenameParameters() {
    }

    public void validate()
            throws IllegalArgumentException {

        if ((stackOwner == null) || (stackOwner.trim().isEmpty())) {
            throw new IllegalArgumentException("--stackOwner must be defined");
        }

        if ((sourceNamePattern == null) || (sourceNamePattern.trim().isEmpty())) {
            throw new IllegalArgumentException("--sourceNamePattern must be defined");
        }

        if ((targetNamePattern == null) || (targetNamePattern.trim().isEmpty())) {
            throw new IllegalArgumentException("--targetNamePattern must be defined");
        }

        buildSourceNamePattern(); // throws exception if the pattern is invalid
    }

    public Pattern buildSourceNamePattern()
            throws IllegalArgumentException {
        try {
            return Pattern.compile(sourceNamePattern);
        } catch (final PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid --sourceNamePattern '" + sourceNamePattern + "'", e);
        }
    }

    @Override
    public String toString() {
        return "{owner='" + stackOwner + '\'' +
               ", sourceNamePattern='" + sourceNamePattern + '\'' +
               ", targetNamePattern='" + targetNamePattern + '\'' +
               '}';
    }
}
