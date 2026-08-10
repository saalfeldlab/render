package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parameters for renaming match collections.
 */
@Parameters
public class MatchCollectionRenameParameters
        implements Serializable {

    @Parameter(
            names = "--matchCollectionOwner",
            description = "Owner of the match collections to rename")
    public String owner;

    @Parameter(
            names = "--sourceNamePattern",
            description = "Regular expression for the names of the collections to rename " +
                          "(e.g. '(w6.*_gc_icc)_match')")
    public String sourceNamePattern;

    @Parameter(
            names = "--targetNamePattern",
            description = "Replacement for renamed collections that can reference capture groups " +
                          "from the sourceNamePattern (e.g. '$1_par_match' renames " +
                          "w61_s140_r00_gc_icc_match to w61_s140_r00_gc_icc_par_match)")
    public String targetNamePattern;

    public MatchCollectionRenameParameters() {
    }

    public void validate()
            throws IllegalArgumentException {

        if ((owner == null) || (owner.trim().isEmpty())) {
            throw new IllegalArgumentException("--matchCollectionOwner must be defined");
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
        return "{owner='" + owner + '\'' +
               ", sourceNamePattern='" + sourceNamePattern + '\'' +
               ", targetNamePattern='" + targetNamePattern + '\'' +
               '}';
    }
}
