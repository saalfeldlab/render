package org.janelia.render.client;

import com.google.common.io.Files;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackVersion;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests the {@link CoordinateClient} class.
 *
 * @author Eric Trautman
 */
public class CoordinateClientTest {

    private File targetSwcDirectory;

    @After
    public void tearDown() throws Exception {
        if (targetSwcDirectory != null) {
            MipmapClientTest.deleteRecursive(targetSwcDirectory);
        }
    }

    @Test
    public void testRoundTripMapping() throws Exception {
        testRoundTripMapping(1);
    }

    @Test
    public void testMultiThreadedRoundTripMapping() throws Exception {
        testRoundTripMapping(3);
    }

    @Test
    public void testMissingCoordinatesErrors() throws Exception {
        final String stackName = "test-stack";
        final Double z = 9.9;
        final CoordinateClient client = new CoordinateClient(stackName, z, null, 1);

        final List<List<TileCoordinates>> worldListOfLists = new ArrayList<>();

        final String nullTileIdForOutOfBoundsCoordinate = null;
        worldListOfLists.add(
                Collections.singletonList(TileCoordinates.buildWorldInstance(nullTileIdForOutOfBoundsCoordinate,
                                                                             new double[]{0, 0})));
        worldListOfLists.add(
                Collections.singletonList(TileCoordinates.buildWorldInstance(nullTileIdForOutOfBoundsCoordinate,
                                                                             new double[]{1, 1})));

        final List<TransformSpec> emptyTransformSpecList = new ArrayList<>();
        final List<TileSpec> emptyTileSpecList = new ArrayList<>();
        final ResolvedTileSpecCollection tiles = new ResolvedTileSpecCollection(emptyTransformSpecList,
                                                                                emptyTileSpecList);

        final List<List<TileCoordinates>> localListOfLists = client.worldToLocal(worldListOfLists, tiles);

        Assert.assertEquals("invalid number of local lists returned",
                            worldListOfLists.size(), localListOfLists.size());

        List<TileCoordinates> localList;
        TileCoordinates localCoordinates;
        String errorMessage;
        for (int i = 0; i < 2; i++) {

            localList = localListOfLists.get(i);
            Assert.assertEquals("invalid number of coordinates in local list " + i,
                                1, localList.size());

            localCoordinates = localList.get(0);
            Assert.assertNull("tileId for coordinates in local list " + i + " should be null",
                              localCoordinates.getTileId());
            Assert.assertNull("local coordinates in local list " + i + " should be null",
                              localCoordinates.getLocal());

            errorMessage = localCoordinates.getError();
            Assert.assertNotNull("local coordinates in local list " + i + " should have error",
                                 errorMessage);
            Assert.assertTrue("local coordinates in local list " + i + " has invalid error message '" + errorMessage + "'",
                              errorMessage.matches("no tile.*" + i + ".0,.*"));
        }

    }


    /**
     * This test is "ignored" because of the dependency on a real web server.
     * It can be configured to run as needed in specific environments.
     *
     * @throws Exception
     *   if any unexpected failures occur.
     */
    @Test
    @Ignore
    public void testRealClient()
            throws Exception {

        final Double z = 3451.0;

        final RenderDataClient renderDataClient =
                new RenderDataClient("http://tem-services:8080/render-ws/v1", "flyTEM", "FAFB00");
        final CoordinateClient client = new CoordinateClient("v13_align_tps", z, renderDataClient, 1);

        final TileCoordinates worldCoord =
                TileCoordinates.buildWorldInstance(null, new double[]{49600.0, 135500.0});

        final List<TileCoordinates> worldList = new ArrayList<>();
        worldList.add(worldCoord);

        final List<List<TileCoordinates>> worldListOfLists = client.getWorldCoordinatesWithTileIds(worldList);

        Assert.assertEquals("invalid number of world lists returned", 1, worldListOfLists.size());

        final List<TileCoordinates> returnedWorldList = worldListOfLists.get(0);

        Assert.assertEquals("invalid number of coordinates in first world list", 3, returnedWorldList.size());

        final TileCoordinates returnedWorldCoord = returnedWorldList.get(0);

        Assert.assertFalse("returned world coordinates have error: " + returnedWorldCoord.toJson(),
                           returnedWorldCoord.hasError());

        Assert.assertEquals("invalid tileId returned",
                            "150226193751108009.3451.0", returnedWorldCoord.getTileId());
    }

    @Test
    public void testGetBatchIndexes()
            throws Exception {
        validateBatchIndexes(3, 10, 4, 4, 2);
        validateBatchIndexes(3, 11, 4, 4, 3);
        validateBatchIndexes(3, 12, 4, 4, 4);
        validateBatchIndexes(3, 1, 2, 1, 1);
        validateBatchIndexes(3, 3, 4, 1, 1);
        validateBatchIndexes(1, 3, 2, 3, 3);
    }

    @Test
    public void testSwcHelper()
            throws Exception {

        final StackVersion stackVersion = new StackVersion(new Date(), null, null, null, 4.0, 4.0, 35.0, null, null);
        final CoordinateClient.SWCHelper swcHelper = new CoordinateClient.SWCHelper(stackVersion, stackVersion);
        final List<TileCoordinates> coordinatesList = new ArrayList<>();

        final String swcSourceDirectoryPath = "src/test/resources/swc";
        swcHelper.addCoordinatesForAllFilesInDirectory(swcSourceDirectoryPath,
                                                       coordinatesList);

        Assert.assertEquals("invalid number of coordinates parsed from swc directory", 99, coordinatesList.size());

        targetSwcDirectory = MipmapClientTest.createTestDirectory("target_swc");

        swcHelper.saveMappedResults(coordinatesList,
                                    targetSwcDirectory.getAbsolutePath());

        final String swcFileName = "8881_swc.swc";
        final File sourceFile = new File(swcSourceDirectoryPath, swcFileName);
        final File targetFile = new File(targetSwcDirectory, swcFileName);
        Assert.assertTrue(targetFile.getAbsolutePath() + " was not saved", targetFile.exists());

        final String beforeText = Files.toString(sourceFile, Charset.defaultCharset());
        final String afterText = Files.toString(targetFile, Charset.defaultCharset());

        final String hackedAfterTextForComparison = afterText.replaceAll("\\.0", "");

        Assert.assertEquals(swcFileName + " contents should be the same", beforeText, hackedAfterTextForComparison);
    }

    private void validateBatchIndexes(final int threads,
                                      final int size,
                                      final int expectedNumberOfIndexes,
                                      final int expectedFirstDelta,
                                      final int expectedLastDelta) {

        final List<Integer> list = CoordinateClient.getBatchIndexes(threads, size);

        final String context = threads + " threads and " + size + " items";
        Assert.assertEquals("invalid number of batch indexes returned for " + context,
                            expectedNumberOfIndexes, list.size());
        Assert.assertEquals("invalid delta between first and second indexes " + context,
                            expectedFirstDelta, (list.get(1) - list.get(0)));
        Assert.assertEquals("invalid delta between last and second-to-last indexes " + context,
                            expectedLastDelta, (list.get(list.size() - 1) - list.get(list.size() - 2)));
    }

    private void testRoundTripMapping(final int numberOfThreads)
            throws Exception {

        final String stackName = "test-stack";
        final Double z = 9.9;
        final CoordinateClient client = new CoordinateClient(stackName, z, null, numberOfThreads);

        final List<List<TileCoordinates>> worldListOfLists = new ArrayList<>();
        final List<TileSpec> tileSpecList = new ArrayList<>();
        // use same tile to make sure concurrent access doesn't break coordinate mapping
        final TileSpec tile = getTileSpec("tile-1", z);
        tileSpecList.add(tile);

        for (int i = 0; i < numberOfThreads; i++) {
            final TileCoordinates worldCoord = TileCoordinates.buildWorldInstance(tile.getTileId(),
                                                                                  new double[]{i, (i+1), z});
            worldListOfLists.add(Collections.singletonList(worldCoord));
        }

        final ResolvedTileSpecCollection tiles = new ResolvedTileSpecCollection(new ArrayList<TransformSpec>(),
                                                                                tileSpecList);
        final List<List<TileCoordinates>> localListOfLists = client.worldToLocal(worldListOfLists, tiles);

        Assert.assertEquals("invalid number of local lists returned",
                            worldListOfLists.size(), localListOfLists.size());

        final double acceptableDelta = 0.001;
        for (int i = 0; i < worldListOfLists.size(); i++) {
            final List<TileCoordinates> worldList = worldListOfLists.get(i);
            final List<TileCoordinates> localList = localListOfLists.get(i);

            Assert.assertEquals("invalid number of coordinates in worldList[" + i + "]", 1, worldList.size());
            Assert.assertEquals("invalid number of coordinates in localList[" + i + "]", 1, localList.size());

            final TileCoordinates worldCoord = worldList.get(0);
            final TileCoordinates localCoord = localList.get(0);

            Assert.assertFalse("returned local coordinates for localList[" + i +
                               "] have error: " + localCoord.toJson(),
                               localCoord.hasError());

            Assert.assertEquals("invalid local x coordinate returned for localList[" + i + "]",
                                worldCoord.getWorld()[0], localCoord.getLocal()[0], acceptableDelta);

            Assert.assertEquals("invalid local y coordinate returned for localList[" + i + "]",
                                worldCoord.getWorld()[1], localCoord.getLocal()[1], acceptableDelta);
        }

        final List<TileCoordinates> roundTripWorldList = client.localToWorld(localListOfLists, tiles);

        Assert.assertEquals("incorrect number of round trip world coordinates",
                            worldListOfLists.size(), roundTripWorldList.size());

        for (int i = 0; i < worldListOfLists.size(); i++) {
            final String context = "worldListOfLists[" + i + "]";
            final List<TileCoordinates> worldList = worldListOfLists.get(i);
            final TileCoordinates worldCoord = worldList.get(0);

            final TileCoordinates roundTripWorldCoord = roundTripWorldList.get(i);
            Assert.assertEquals("incorrect round trip tile id for " + context,
                                worldCoord.getTileId(), roundTripWorldCoord.getTileId());

            final double[] expectedArray = worldCoord.getWorld();
            final double[] actualArray = roundTripWorldCoord.getWorld();
            Assert.assertEquals("incorrect round trip world array length for " + context,
                                expectedArray.length, actualArray.length);
            for (int j = 0; j < expectedArray.length; j++) {
                Assert.assertEquals("incorrect round trip value for item " + j + " in " + context,
                                    expectedArray[j], actualArray[j], 0.01);
            }
        }

    }

    private TileSpec getTileSpec(final String tileId,
                                 final double z) {
        final String tile1json = "{ \"tileId\": \"" + tileId + "\", \"z\": " + z +
                                 ", \"width\": 2560, \"height\": 2160}";
        return TileSpec.fromJson(tile1json);
    }
}
