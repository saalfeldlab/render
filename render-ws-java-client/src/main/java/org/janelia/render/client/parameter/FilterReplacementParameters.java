package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.janelia.alignment.spec.stack.StackId;

/**
 * Parameters for replacing the filter specs of each tile in one or more stacks with
 * the filter specs of the same tiles in other stacks.
 *
 * <p>The filter and target stack names are both derived from each tile stack name using
 * the {@link #tileStackPattern}, so the replacements can share its capture groups.
 * For example, the pattern '^(.*_aso)i(_3d_s)$' with filter replacement '$1' and
 * target replacement '$1$2' reads filters for w61_s070_r00_gc_bc_par_cc_asoi_3d_s
 * from w61_s070_r00_gc_bc_par_cc_aso and saves the result to w61_s070_r00_gc_bc_par_cc_aso_3d_s.</p>
 */
@Parameters
public class FilterReplacementParameters
        implements Serializable {

    @Parameter(
            names = "--tileStackPattern",
            description = "Regular expression that identifies the tile stacks to process and is used to derive " +
                          "the names of their filter and target stacks (e.g. '^(.*_aso)i(_3d_s)$')")
    public String tileStackPattern;

    @Parameter(
            names = "--filterStackReplacement",
            description = "Replacement that derives the name of the stack (in the same project) with " +
                          "the filters to use and can reference capture groups from the tileStackPattern " +
                          "(e.g. '$1' derives w61_s070_r00_gc_bc_par_cc_aso from w61_s070_r00_gc_bc_par_cc_asoi_3d_s)")
    public String filterStackReplacement;

    @Parameter(
            names = "--targetStackReplacement",
            description = "Replacement that derives the name of the stack (in the same project) where tiles " +
                          "with replaced filters are saved and can reference capture groups from the " +
                          "tileStackPattern (e.g. '$1$2' derives w61_s070_r00_gc_bc_par_cc_aso_3d_s " +
                          "from w61_s070_r00_gc_bc_par_cc_asoi_3d_s)")
    public String targetStackReplacement;

    public FilterReplacementParameters() {
    }

    /** @return true if the specified stack name matches the tileStackPattern. */
    public boolean isTileStack(final String stack) {
        return buildTileStackPattern().matcher(stack).matches();
    }

    /**
     * @return the id of the stack with filters for the specified tile stack.
     *
     * @throws IllegalArgumentException
     *   if the tile stack name does not match the tileStackPattern.
     */
    public StackId getFilterStackId(final StackId tileStackId)
            throws IllegalArgumentException {
        return deriveStackId(tileStackId, filterStackReplacement);
    }

    /**
     * @return the id of the stack where the specified tile stack's tiles with replaced filters are saved.
     *
     * @throws IllegalArgumentException
     *   if the tile stack name does not match the tileStackPattern or
     *   if the derived target stack is the tile stack or the filter stack.
     */
    public StackId getTargetStackId(final StackId tileStackId)
            throws IllegalArgumentException {
        final StackId targetStackId = deriveStackId(tileStackId, targetStackReplacement);
        final StackId filterStackId = getFilterStackId(tileStackId);
        // saving to either source stack would change the data that this replacement (or a rerun) reads
        if (targetStackId.equals(tileStackId) || targetStackId.equals(filterStackId)) {
            throw new IllegalArgumentException("target stack " + targetStackId.toDevString() +
                                               " derived for " + tileStackId.toDevString() +
                                               " must differ from both the tile stack and the filter stack " +
                                               filterStackId.toDevString());
        }
        return targetStackId;
    }

    public void validate()
            throws IllegalArgumentException {

        if ((tileStackPattern == null) || (tileStackPattern.trim().isEmpty())) {
            throw new IllegalArgumentException("tileStackPattern must be defined");
        }

        if ((filterStackReplacement == null) || (filterStackReplacement.trim().isEmpty())) {
            throw new IllegalArgumentException("filterStackReplacement must be defined");
        }

        if ((targetStackReplacement == null) || (targetStackReplacement.trim().isEmpty())) {
            throw new IllegalArgumentException("targetStackReplacement must be defined");
        }

        buildTileStackPattern(); // throws exception if the pattern is invalid
    }

    private StackId deriveStackId(final StackId tileStackId,
                                  final String replacement)
            throws IllegalArgumentException {
        final Matcher matcher = buildTileStackPattern().matcher(tileStackId.getStack());
        if (! matcher.matches()) {
            throw new IllegalArgumentException("cannot derive filter and target stacks for " +
                                               tileStackId.toDevString() + " because its name does not " +
                                               "match the tileStackPattern '" + tileStackPattern + "'");
        }
        return new StackId(tileStackId.getOwner(),
                           tileStackId.getProject(),
                           matcher.replaceFirst(replacement));
    }

    private Pattern buildTileStackPattern()
            throws IllegalArgumentException {
        try {
            return Pattern.compile(tileStackPattern);
        } catch (final PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid tileStackPattern '" + tileStackPattern + "'", e);
        }
    }

    @Override
    public String toString() {
        return "{tileStackPattern='" + tileStackPattern + '\'' +
               ", filterStackReplacement='" + filterStackReplacement + '\'' +
               ", targetStackReplacement='" + targetStackReplacement + '\'' +
               '}';
    }
}
