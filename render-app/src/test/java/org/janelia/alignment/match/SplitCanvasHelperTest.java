/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link SplitCanvasHelper} class.
 *
 * @author Eric Trautman
 */
public class SplitCanvasHelperTest {

    @Test
    public void testAddDerivedTileSpecsToCollection()
            throws Exception {

        final SplitCanvasHelper splitCanvasHelper = new SplitCanvasHelper();
        splitCanvasHelper.trackSplitCanvases(CanvasMatches.fromJsonArray(MATCHES_JSON));

        final List<Double> zValues = splitCanvasHelper.getSortedZValues();
        Assert.assertEquals("invalid number of z values", 2, zValues.size());

        for (int i = 0; i < zValues.size(); i++) {
            final Double z = zValues.get(i);
            final ResolvedTileSpecCollection resolvedTiles = ResolvedTileSpecCollection.fromJson(RESOLVED_JSON[i]);
            Assert.assertEquals("invalid number of original tiles", 1, resolvedTiles.getTileCount());

            splitCanvasHelper.addDerivedTileSpecsToCollection(z, resolvedTiles);
            Assert.assertEquals("invalid number of tiles after derivation", 3, resolvedTiles.getTileCount());

            resolvedTiles.removeTileSpecs(splitCanvasHelper.getOriginalIdsForZ(z));
            Assert.assertEquals("invalid number of tiles after clean-up", 2, resolvedTiles.getTileCount());
        }

    }

    @Test
    public void testDeriveSplitMatchesForConsensusSetCanvases()
            throws Exception {

        // actually tests usage of split canvas helper in hierarchical client

        final String[] multiConsensusGroupIds = { "2213.0", "2214.0"};
        final String[] outsideJson = { ConsensusSetPairsTest.OUTSIDE_2213, ConsensusSetPairsTest.OUTSIDE_2214 };

        final SplitCanvasHelper splitCanvasHelper = new SplitCanvasHelper();

        final Set<CanvasMatches> matchPairs = new TreeSet<>();

        for (int i = 0; i < multiConsensusGroupIds.length; i++) {
            matchPairs.addAll(CanvasMatches.fromJsonArray(outsideJson[i]));
        }

        for (final String groupId : multiConsensusGroupIds) {
            final DerivedMatchGroup derivedMatchGroup = new DerivedMatchGroup(groupId, matchPairs);
            final List<CanvasMatches> derivedMatchPairs = derivedMatchGroup.getDerivedPairs();
            // deleteMatchesOutsideGroup(groupId);
            // saveMatches(derivedMatchPairs);

            splitCanvasHelper.trackSplitCanvases(derivedMatchPairs);
        }

        final List<Double> zValues = splitCanvasHelper.getSortedZValues();
        Assert.assertEquals("invalid number of split z values", 3, zValues.size());

        final String[] tilesJson = { TILES_2213, TILES_2214, TILES_2215 };

        final List<String> finalTileIds = new ArrayList<>();
        for (int i = 0; i < zValues.size(); i++) {
            final Double z = zValues.get(i);
            final ResolvedTileSpecCollection resolvedTiles = ResolvedTileSpecCollection.fromJson(tilesJson[i]);
            splitCanvasHelper.addDerivedTileSpecsToCollection(z, resolvedTiles);
            resolvedTiles.removeTileSpecs(splitCanvasHelper.getOriginalIdsForZ(z));
            finalTileIds.addAll(
                    resolvedTiles.getTileSpecs()
                            .stream()
                            .map(TileSpec::getTileId)
                            .collect(Collectors.toList()));
            // deleteStack(tierStackName, z);
            // saveResolvedTiles(resolvedTiles, tierStackName, z);
        }

        Assert.assertEquals("invalid number of tile specs", 6, finalTileIds.size());
        System.out.println(finalTileIds);
    }

    private static final String MATCHES_JSON =
            "[\n" +
            "  {\n" +
            "    \"pGroupId\": \"2213.0\",\n" +
            "    \"pId\": \"z_2213.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\",\n" +
            "    \"qGroupId\": \"2214.0\",\n" +
            "    \"qId\": \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0\",\n" +
            "    \"consensusSetData\": {\n" +
            "      \"index\": 0,\n" +
            "      \"originalPId\": \"z_2213.0_box_40532_42182_3731_3654_0.274457\",\n" +
            "      \"originalQId\": \"z_2214.0_box_40532_42182_3731_3654_0.274457\"\n" +
            "    },\n" +
            "    \"matches\": {\n" +
            "      \"p\": [ [ 908.707000726556, 33.56914252026278 ], [ 959.3971692751488, 735.4256840366271 ] ],\n" +
            "      \"q\": [ [ 913.1264670919462, 34.123888662871146 ], [ 950.8089280897713, 730.8114013147468 ] ],\n" +
            "      \"w\": [ 1, 1 ]\n" +
            "    }\n" +
            "  },\n" +
            "  {\n" +
            "    \"pGroupId\": \"2213.0\",\n" +
            "    \"pId\": \"z_2213.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\",\n" +
            "    \"qGroupId\": \"2214.0\",\n" +
            "    \"qId\": \"z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_1\",\n" +
            "    \"consensusSetData\": {\n" +
            "      \"index\": 1,\n" +
            "      \"originalPId\": \"z_2213.0_box_40532_42182_3731_3654_0.274457\",\n" +
            "      \"originalQId\": \"z_2214.0_box_40532_42182_3731_3654_0.274457\"\n" +
            "    },\n" +
            "    \"matches\": {\n" +
            "      \"p\": [ [ 153.46403447698574, 208.5014594073511 ], [ 991.7140519868069, 936.007876044333 ] ],\n" +
            "      \"q\": [ [ 152.0155156417605, 215.916838574884 ], [ 989.6111234216183, 928.614990738921 ] ],\n" +
            "      \"w\": [ 1, 1 ]\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private static final String[] RESOLVED_JSON = {
            "{\n" +
            "  \"transformIdToSpecMap\": {},\n" +
            "  \"tileIdToSpecMap\": {\n" +
            "    \"z_2213.0_box_40532_42182_3731_3654_0.274457\": {\n" +
            "      \"tileId\": \"z_2213.0_box_40532_42182_3731_3654_0.274457\",\n" +
            "      \"layout\": { \"sectionId\": \"2213.0\" },\n" +
            "      \"z\": 2213, \"minX\": 0, \"minY\": 0, \"maxX\": 1024, \"maxY\": 1003,\n" +
            "      \"width\": 1024, \"height\": 1003,\n" +
            "      \"channels\": [\n" +
            "        {\n" +
            "          \"mipmapLevels\": {\n" +
            "            \"0\": {\n" +
            "              \"imageUrl\": \"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_j/z/2213.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520006731086&name=z2213.tif\"\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      ],\n" +
            "      \"transforms\": {\n" +
            "        \"type\": \"list\",\n" +
            "        \"specList\": [\n" +
            "          {\n" +
            "            \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "            \"dataString\": \"1 0 0 1 0 0\"\n" +
            "          }\n" +
            "        ]\n" +
            "      },\n" +
            "      \"meshCellSize\": 64\n" +
            "    }\n" +
            "  }\n" +
            "}",
            "{\n" +
            "  \"transformIdToSpecMap\": {},\n" +
            "  \"tileIdToSpecMap\": {\n" +
            "    \"z_2214.0_box_40532_42182_3731_3654_0.274457\": {\n" +
            "      \"tileId\": \"z_2214.0_box_40532_42182_3731_3654_0.274457\",\n" +
            "      \"layout\": { \"sectionId\": \"2214.0\" },\n" +
            "      \"z\": 2214, \"minX\": 0, \"minY\": 0, \"maxX\": 1024, \"maxY\": 1003,\n" +
            "      \"width\": 1024, \"height\": 1003,\n" +
            "      \"channels\": [\n" +
            "        {\n" +
            "          \"mipmapLevels\": {\n" +
            "            \"0\": {\n" +
            "              \"imageUrl\": \"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_j/z/2214.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520006731086&name=z2214.tif\"\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      ],\n" +
            "      \"transforms\": {\n" +
            "        \"type\": \"list\",\n" +
            "        \"specList\": [\n" +
            "          {\n" +
            "            \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "            \"dataString\": \"1 0 0 1 0 0\"\n" +
            "          }\n" +
            "        ]\n" +
            "      },\n" +
            "      \"meshCellSize\": 64\n" +
            "    }\n" +
            "  }\n" +
            "}"
    };

    private static final String TILES_2213 =
            "{\"transformIdToSpecMap\":{},\"tileIdToSpecMap\":{\"z_2213.0_box_40532_42182_3731_3654_0.274457\":{\"tileId\":\"z_2213.0_box_40532_42182_3731_3654_0.274457\",\"layout\":{\"sectionId\":\"2213.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2213.0,\"minX\":0.0,\"minY\":0.0,\"maxX\":1024.0,\"maxY\":1003.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_j/z/2213.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520030615158&name=z2213.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"metaData\":{\"labels\":[\"regular\"]},\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"1 0 0 1 0 0\"}]},\"meshCellSize\":64.0}}}";
    private static final String TILES_2214 =
            "{\"transformIdToSpecMap\":{},\"tileIdToSpecMap\":{\"z_2214.0_box_40532_42182_3731_3654_0.274457\":{\"tileId\":\"z_2214.0_box_40532_42182_3731_3654_0.274457\",\"layout\":{\"sectionId\":\"2214.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2214.0,\"minX\":0.0,\"minY\":0.0,\"maxX\":1024.0,\"maxY\":1003.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_j/z/2214.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520030615158&name=z2214.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"metaData\":{\"labels\":[\"regular\"]},\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"1 0 0 1 0 0\"}]},\"meshCellSize\":64.0}}}";
    private static final String TILES_2215 =
            "{\"transformIdToSpecMap\":{},\"tileIdToSpecMap\":{\"z_2215.0_box_40532_42182_3731_3654_0.274457\":{\"tileId\":\"z_2215.0_box_40532_42182_3731_3654_0.274457\",\"layout\":{\"sectionId\":\"2215.0\",\"temca\":\"n/a\",\"camera\":\"n/a\",\"imageRow\":0,\"imageCol\":0,\"stageX\":0.0,\"stageY\":0.0,\"rotation\":0.0},\"z\":2215.0,\"minX\":0.0,\"minY\":0.0,\"maxX\":1024.0,\"maxY\":1003.0,\"width\":1024.0,\"height\":1003.0,\"mipmapLevels\":{},\"channels\":[{\"minIntensity\":0.0,\"maxIntensity\":255.0,\"mipmapLevels\":{\"0\":{\"imageUrl\":\"http://renderer.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold/stack/rough_tiles_j/z/2215.0/box/40532,42182,3731,3654,0.27445725006700616/tiff-image?v=1520030615158&name=z2215.tif\"}}}],\"transforms\":{\"type\":\"list\",\"specList\":[{\"type\":\"leaf\",\"metaData\":{\"labels\":[\"regular\"]},\"className\":\"mpicbg.trakem2.transform.AffineModel2D\",\"dataString\":\"1 0 0 1 0 0\"}]},\"meshCellSize\":64.0}}}";

}