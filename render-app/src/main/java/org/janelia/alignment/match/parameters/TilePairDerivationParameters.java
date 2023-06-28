package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.MontageRelativePosition;

/**
 * Common parameters used for location based tile pair derivation.
 *
 * @author Eric Trautman
 */
public class TilePairDerivationParameters implements Serializable {

    @Parameter(
            names = "--xyNeighborFactor",
            description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles"
    )
    public Double xyNeighborFactor = 0.9;

    @Parameter(
            names = "--explicitRadius",
            description = "Explicit radius in full scale pixels for locating neighbor tiles (if set, will override --xyNeighborFactor)"
    )
    public Double explicitRadius;

    @Parameter(
            names = "--useRowColPositions",
            description = "For montage pairs (zNeighborDistance == 0) use layout imageRow and imageCol values instead of tile bounds to identify neighbor tiles",
            arity = 0)
    public boolean useRowColPositions = false;

    @Parameter(
            names = "--zNeighborDistance",
            description = "Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value"
    )
    public Integer zNeighborDistance = 2;

    @Parameter(
            names = "--excludeCornerNeighbors",
            description = "Exclude neighbor tiles whose center x and y is outside the source tile's x and y range respectively",
            arity = 1)
    public boolean excludeCornerNeighbors = true;

    @Parameter(
            names = "--excludeCompletelyObscuredTiles",
            description = "Exclude tiles that are completely obscured by reacquired tiles",
            arity = 1)
    public boolean excludeCompletelyObscuredTiles = true;

    @Parameter(
            names = "--excludeSameLayerNeighbors",
            description = "Exclude neighbor tiles in the same layer (z) as the source tile",
            arity = 1)
    public boolean excludeSameLayerNeighbors = false;

    @Parameter(
            names = "--excludeSameSectionNeighbors",
            description = "Exclude neighbor tiles with the same sectionId as the source tile",
            arity = 1)
    public boolean excludeSameSectionNeighbors = false;

    @Parameter(
            names = "--excludePairsInMatchCollection",
            description = "Name of match collection whose existing pairs should be excluded from the generated list (default is to include all pairs)"
    )
    public String excludePairsInMatchCollection;

    @Parameter(
            names = "--excludeSameLayerPairsWithPosition",
            description = "Exclude same layer pairs that have one tile with the specified position"
    )
    public MontageRelativePosition excludeSameLayerPairsWithPosition;

    @Parameter(
            names = "--existingMatchOwner",
            description = "Owner of match collection whose existing pairs should be excluded from the generated list (default is owner)"
    )
    public String existingMatchOwner;

    @Parameter(names = "--minExistingMatchCount", description = "Minimum number of existing matches to trigger pair exclusion")
    public Integer minExistingMatchCount = 0;

    @Parameter(
            names = "--onlyIncludeTilesFromStack",
            description = "Name of stack containing tile ids to include (uses --owner and --project values, default is to include all tiles)"
    )
    public String onlyIncludeTilesFromStack;

    @Parameter(
            names = "--onlyIncludeTilesNearTileIdsJson",
            description = "Path of JSON file containing array of source tile ids.  Only pairs for tiles near these tiles will be included (default is to include all nearby tiles)."
    )
    public String onlyIncludeTilesNearTileIdsJson;

    public TilePairDerivationParameters() {
    }

}
