package org.janelia.render.service.dao;

import java.util.List;

import org.bson.Document;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests {@link MatchDao} methods that don't make database calls and therefore don't need an embedded mongodb instance.
 *
 * @author Eric Trautman
 */
public class MatchDaoWithoutEmbeddedMongoTest {

    @Test
    public void testBuildMatchQueryListForTiles() {

        final ResolvedTileSpecCollection tileSpecs = new ResolvedTileSpecCollection();

        // section1 and section2 each have one tile
        tileSpecs.addTileSpecToCollection(buildTileSpec("a", "tile.a.0"));
        tileSpecs.addTileSpecToCollection(buildTileSpec("b", "tile.b.0"));
        for (int i = 0; i < 10; i++) { // add ten tiles for section3
            tileSpecs.addTileSpecToCollection(buildTileSpec("c", "tile.c." + i));
        }
        for (int i = 0; i < 3; i++) { // add three tiles for section4
            tileSpecs.addTileSpecToCollection(buildTileSpec("d", "tile.d." + i));
        }

        final List<Document> queryList = MatchDao.buildMatchQueryListForTiles(tileSpecs,
                                                                              4);
        Assert.assertEquals("invalid number of queries", 4, queryList.size());

        LOG.info("testBuildMatchQueryListForTiles: queryList is {}", queryList);
    }

    public static TileSpec buildTileSpec(final String sectionId,
                                         final String tileId) {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(new LayoutData(sectionId, null, null, 0, 0, 0.0, 0.0, null));
        return tileSpec;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchDaoWithoutEmbeddedMongoTest.class);
}
