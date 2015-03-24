package org.janelia.alignment.spec;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TileCoordinates} class.
 *
 * @author Eric Trautman
 */
public class TileCoordinatesTest {

    @Test
    public void testJsonProcessing() throws Exception {

        List<List<TileCoordinates>> listOfLists = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<TileCoordinates> list = new ArrayList<>();
            for (int j = 10; j < 13; j++) {
                list.add(TileCoordinates.buildLocalInstance("test-tile-" + i + "-" + j,
                                                            new double[] {i, j, 9.0}));
            }
            listOfLists.add(list);
        }

        final String json = JsonUtils.GSON.toJson(listOfLists);

        final List<List<TileCoordinates>> parsedListOfLists =
                TileCoordinates.fromJsonArrayOfArrays(new StringReader(json));

        Assert.assertEquals("invalid number of lists parsed", listOfLists.size(), parsedListOfLists.size());

        for (int i = 0; i < parsedListOfLists.size(); i++) {
            List<TileCoordinates> parsedList = parsedListOfLists.get(i);
            Assert.assertTrue("parsed list " + i + " is empty", parsedList.size() > 0);
            Object parsedObject = parsedList.get(0);
            //noinspection ConstantConditions
            Assert.assertTrue("parsed list " + i + " has item with type " + parsedObject.getClass(),
                              parsedObject instanceof TileCoordinates);

        }

    }

}
