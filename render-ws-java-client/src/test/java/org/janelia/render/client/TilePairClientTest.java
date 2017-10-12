package org.janelia.render.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.annotation.Nonnull;

import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
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
    public void testMontageDeriveAndSaveSortedNeighborPairs() throws Exception {

        final int zNeighborDistance = 0;

        // 5 double tile layers with distance 0 =>
        //   1 + 1 + 1 + 1 + 1 = 5 total pairs =>
        //     1 files with 3 pairs + 1 file with 2 pairs
        final int expectedNumberOfFiles = 2;

        testDeriveAndSaveSortedNeighborPairs(zNeighborDistance, expectedNumberOfFiles);
    }

    @Test
    public void testCrossDeriveAndSaveSortedNeighborPairs() throws Exception {

        final int zNeighborDistance = 2;

        // 5 double tile layers with distance 2 =>
        //   9 + 9 + 9 + 5 + 1 = 33 total pairs =>
        //     11 files with 3 pairs
        final int expectedNumberOfFiles = 11;

        testDeriveAndSaveSortedNeighborPairs(zNeighborDistance, expectedNumberOfFiles);
    }

    private void testDeriveAndSaveSortedNeighborPairs(final int zNeighborDistance,
                                                      final int expectedNumberOfFiles) throws Exception {

        final String toJson = baseFileName + ".json";

        final MockTilePairClient client = new MockTilePairClient(getTestParameters(zNeighborDistance, toJson));
        client.deriveAndSaveSortedNeighborPairs();

        final List<Path> pairFilePaths = new ArrayList<>();
        Files.list(Paths.get(".")).forEach(path -> {
            if (path.getFileName().toString().startsWith(baseFileName)) {
                pairFilePaths.add(path);
            }
        });

        // 8 single tile layers with distance 2 =>
        //   2 + 2 + 2 + 2 + 2 + 2 + 1 = 13 total pairs =>
        //     4 files with 3 pairs + 1 file with 1 pair
        Assert.assertEquals("invalid number of pairs files created", expectedNumberOfFiles, pairFilePaths.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(TilePairClientTest.class);

    private static TilePairClient.Parameters getTestParameters(final int zNeighborDistance,
                                                               final String toJson)
            throws IllegalArgumentException {
        final TilePairClient.Parameters p = new TilePairClient.Parameters();
        final String argString = "--baseDataUrl u --owner o --project p --stack s --minZ 1 --maxZ 8 " +
                                 "--excludeCornerNeighbors false --maxPairsPerFile 3 " +
                                 "--zNeighborDistance " + zNeighborDistance +
                                 " --toJson " + toJson;
        p.parse(argString.split(" "), TilePairClient.class);
        return p;
    }

    private static class MockTilePairClient extends TilePairClient {

        public MockTilePairClient(final TilePairClient.Parameters p)
                throws IllegalArgumentException {
            super(p);
        }

        @Override
        public List<Double> getZValues()
                throws IOException {
            return Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        }

        @Nonnull
        @Override
        public TileBoundsRTree buildRTree(final double z)
                throws IOException {
            final List<TileBounds> tileBoundsList =
                    Arrays.asList(new TileBounds("a" + z, String.valueOf(z), z, 0.0, 0.0, 11.0, 22.0),
                                  new TileBounds("b" + z, String.valueOf(z), z, 9.0, 0.0, 30.0, 22.0));
            return new TileBoundsRTree(z, tileBoundsList);
        }
    }
}
