package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bundle of tile specs and corresponding match pairs.
 *
 * @author Eric Trautman
 */
public class ResolvedTileSpecsWithMatchPairs
        implements Serializable {

    private final ResolvedTileSpecCollection resolvedTileSpecs;
    private List<CanvasMatches> matchPairs;

    @SuppressWarnings("unused")
    private ResolvedTileSpecsWithMatchPairs() {
        this(null, null);
    }

    public ResolvedTileSpecsWithMatchPairs(final ResolvedTileSpecCollection resolvedTileSpecs,
                                           final List<CanvasMatches> matchPairs) {
        this.resolvedTileSpecs = resolvedTileSpecs;
        this.matchPairs = matchPairs;
    }

    public ResolvedTileSpecCollection getResolvedTileSpecs() {
        return resolvedTileSpecs;
    }

    @JsonIgnore
    public TileSpec getTileSpec(final String tileId) {
        return resolvedTileSpecs.getTileSpec(tileId);
    }

    public List<CanvasMatches> getMatchPairs() {
        return matchPairs;
    }

    @JsonIgnore
    public int getMatchPairCount() {
        return matchPairs == null ? 0 : matchPairs.size();
    }

    /**
     * @return IDs for tiles referenced by match pairs but missing from tile spec collection.
     *           These are considered "padding" tiles because they are completely outside
     *           the bounding box used to select tiles in the collection while still being
     *           connected to tile(s) inside the bounding box.
     */
    public Set<String> findPaddingTileIds() {
        final Set<String> paddingTileIds = new HashSet<>();
        for (final CanvasMatches pair : matchPairs) {
            if (! resolvedTileSpecs.hasTileSpec(pair.getpId())) {
                paddingTileIds.add(pair.getpId());
            }
            if (! resolvedTileSpecs.hasTileSpec(pair.getqId())) {
                paddingTileIds.add(pair.getqId());
            }
        }
        return paddingTileIds;
    }

    /**
     * Normalizes this collection using the specified parameters by resolving all tile specs for client-side usage,
     * removing tiles and match pairs that are too far from each other in z and sorting pairs.
     *
     * @param  maxZDistance   maximum non-negative integral z distance for all retained pairs
     *                        (or null to accept all pairs).
     *
     * @throws IllegalArgumentException
     *   if maxZDistance < 0
     */
    public void normalize(final Integer maxZDistance)
            throws IllegalArgumentException {

        LOG.info("normalize: entry, process {} tiles and {} pairs with maxZDistance {}",
                 resolvedTileSpecs.getTileCount(), matchPairs.size(), maxZDistance);

        if ((maxZDistance != null) && (maxZDistance <= 0)) {
            throw new IllegalArgumentException("maxZDistance must be >= 0 or null");
        }

        resolvedTileSpecs.resolveTileSpecs();

        // remove match pairs that are too far away in z or do not have both tile specs in this collection
        // TODO: keep track of or return removed match pairs in case solver needs to pull adjacent tiles later

        final List<CanvasMatches> normalizedMatchPairs = new ArrayList<>(matchPairs.size());

        for (final CanvasMatches pair : matchPairs) {
            final TileSpec pTileSpec = resolvedTileSpecs.getTileSpec(pair.getpId());
            final TileSpec qTileSpec = resolvedTileSpecs.getTileSpec(pair.getqId());
            if ((pTileSpec != null) && (qTileSpec != null)) {
                if (maxZDistance == null) {
                    normalizedMatchPairs.add(pair);
                } else {
                    if (pTileSpec.zDistanceFrom(qTileSpec) <= maxZDistance) {
                        normalizedMatchPairs.add(pair);
                    }
                }
            }
        }

        // pairs from web service are not sorted, so sort here to make usage loops more intuitive
        Collections.sort(normalizedMatchPairs);

        final String countMsg = normalizedMatchPairs.size() < matchPairs.size() ? "was reduced to" : "remained as";
        LOG.info("resolveTileSpecsAndNormalizeMatchPairs: with maxZDistance {} match pair count of {} {} {}",
                 maxZDistance, matchPairs.size(), countMsg, normalizedMatchPairs.size());

        this.matchPairs = normalizedMatchPairs;
    }

    public static ResolvedTileSpecsWithMatchPairs fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<ResolvedTileSpecsWithMatchPairs> JSON_HELPER =
            new JsonUtils.Helper<>(ResolvedTileSpecsWithMatchPairs.class);

    private static final Logger LOG = LoggerFactory.getLogger(ResolvedTileSpecsWithMatchPairs.class);
}
