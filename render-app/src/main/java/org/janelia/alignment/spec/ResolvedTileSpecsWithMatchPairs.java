package org.janelia.alignment.spec;

import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;

/**
 * A bundle of tile specs and corresponding match pairs.
 *
 * @author Eric Trautman
 */
public class ResolvedTileSpecsWithMatchPairs
        implements Serializable {

    private final ResolvedTileSpecCollection resolvedTileSpecs;
    private List<CanvasMatches> matchPairs;
    private transient boolean isNormalized;

    @SuppressWarnings("unused")
    private ResolvedTileSpecsWithMatchPairs() {
        this(null, null);
    }

    public ResolvedTileSpecsWithMatchPairs(final ResolvedTileSpecCollection resolvedTileSpecs,
                                           final List<CanvasMatches> matchPairs) {
        this.resolvedTileSpecs = resolvedTileSpecs;
        this.matchPairs = matchPairs;
        this.isNormalized = false;
    }

    public ResolvedTileSpecCollection getResolvedTileSpecs() {
        if (! isNormalized) {
            resolveTileSpecsAndNormalizeMatchPairs();
        }
        return resolvedTileSpecs;
    }

    public List<CanvasMatches> getMatchPairs() {
        if (! isNormalized) {
            resolveTileSpecsAndNormalizeMatchPairs();
        }
        return matchPairs;
    }

    private synchronized void resolveTileSpecsAndNormalizeMatchPairs()
            throws IllegalArgumentException {
        if (! isNormalized) {
            resolvedTileSpecs.resolveTileSpecs();
            final List<CanvasMatches> normalizedMatchPairs = new ArrayList<>(matchPairs.size());
            for (final CanvasMatches pair : matchPairs) {
                if (resolvedTileSpecs.hasTileSpec(pair.getpId()) && (resolvedTileSpecs.hasTileSpec(pair.getqId()))) {
                    normalizedMatchPairs.add(pair);
                }
            }
            Collections.sort(normalizedMatchPairs);
            this.matchPairs = normalizedMatchPairs;
            isNormalized = true;
        }
    }

    public static ResolvedTileSpecsWithMatchPairs fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<ResolvedTileSpecsWithMatchPairs> JSON_HELPER =
            new JsonUtils.Helper<>(ResolvedTileSpecsWithMatchPairs.class);
}
