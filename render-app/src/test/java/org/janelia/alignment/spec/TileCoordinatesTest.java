package org.janelia.alignment.spec;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests the {@link TileCoordinates} class.
 *
 * @author Eric Trautman
 */
public class TileCoordinatesTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final List<List<TileCoordinates>> listOfLists = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final List<TileCoordinates> list = new ArrayList<>();
            for (int j = 10; j < 13; j++) {
                list.add(TileCoordinates.buildLocalInstance("test-tile-" + i + "-" + j,
                                                            new double[] {i, j, 9.0}));
            }
            listOfLists.add(list);
        }

        String json = JsonUtils.MAPPER.writeValueAsString(listOfLists);

        final List<List<TileCoordinates>> parsedListOfLists =
                TileCoordinates.fromJsonArrayOfArrays(new StringReader(json));

        assertEquals(listOfLists.size(), parsedListOfLists.size(), "invalid number of lists parsed");

        for (int i = 0; i < parsedListOfLists.size(); i++) {
            final List<TileCoordinates> parsedList = parsedListOfLists.get(i);
            assertFalse(parsedList.isEmpty(), "parsed list " + i + " is empty");
            final Object parsedObject = parsedList.getFirst();
            assertInstanceOf(TileCoordinates.class, parsedObject, "invalid item type in parsed list " + i);
        }

        final List<TileCoordinates> list = listOfLists.getFirst();

        json = JsonUtils.MAPPER.writeValueAsString(list);

        final List<TileCoordinates> parsedList = TileCoordinates.fromJsonArray(new StringReader(json));
        assertEquals(list.size(), parsedList.size(), "invalid number of coordinates parsed");

        for (int i = 0; i < parsedList.size(); i++) {
            final Object parsedObject = parsedList.get(i);
            assertInstanceOf(TileCoordinates.class, parsedObject, "invalid type for parsed item " + i);
        }

    }

}
