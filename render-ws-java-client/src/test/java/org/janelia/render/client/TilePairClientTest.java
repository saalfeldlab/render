package org.janelia.render.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;

/**
 * Tests the {@link TilePairClient} class.
 *
 * @author Eric Trautman
 */
public class TilePairClientTest {

    private String baseFileName;

    @Before
    public void setup() throws Exception {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        this.baseFileName = "test_tile_pairs_" + sdf.format(new Date());
    }

    @After
    public void tearDown() throws Exception {
        Files.list(Paths.get(".")).forEach(path -> {
            if (path.getFileName().toString().startsWith(baseFileName)) {
                try {
                    Files.delete(path);
                    LOG.info("deleted {}", path.toAbsolutePath());
                } catch (final Throwable t) {
                    LOG.warn("failed to delete " + path.toAbsolutePath(), t);
                }
            }
        });
    }

    @Test
    public void testSavePairs() throws Exception {

        final String toJson = baseFileName + ".json";

        final TilePairClient.Parameters p = new TilePairClient.Parameters();
        p.parse(new String[] {
                "--baseDataUrl",     "u",
                "--owner",           "o",
                "--project",         "p",
                "--stack",           "s",
                "--minZ",            "1",
                "--maxZ",            "2",
                "--toJson",          toJson,
                "--maxPairsPerFile", "3"
                },
                TilePairClient.class);

        final List<OrderedCanvasIdPair> neighborPairs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            neighborPairs.add(getPair(i));
        }

        TilePairClient.savePairs(p, "http://template", neighborPairs);

        final List<Path> pairFilePaths = new ArrayList<>();
        Files.list(Paths.get(".")).forEach(path -> {
            if (path.getFileName().toString().startsWith(baseFileName)) {
                pairFilePaths.add(path);
            }
        });

        Assert.assertEquals("invalid number of pairs files created", 3, pairFilePaths.size());
    }

    private OrderedCanvasIdPair getPair(final int index) {
        return new OrderedCanvasIdPair(new CanvasId("ga", String.valueOf(index)),
                                       new CanvasId("gb", String.valueOf(index + 1)));
    }

    private static final Logger LOG = LoggerFactory.getLogger(TilePairClientTest.class);

}
