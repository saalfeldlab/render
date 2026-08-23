package org.janelia.alignment.betterbox;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link BoxData} class.
 *
 * @author Eric Trautman
 */
public class BoxDataTest {

    @Test
    public void testFromString() throws Exception {

        BoxData boxData = BoxData.fromString("1,2.3,4,5,2,0123");

        assertEquals(1, boxData.getLevel(), "invalid level parsed");
        assertEquals(2.3, boxData.getZ(), 0.001, "invalid z parsed");
        assertEquals(4, boxData.getRow(), "invalid row parsed");
        assertEquals(5, boxData.getColumn(), "invalid column parsed");
        assertEquals(2, boxData.getNumberOfSiblings(), "invalid number of siblings parsed");
        assertEquals(4, boxData.getChildCount(), "invalid number of children parsed");

        List<BoxData> children = boxData.getChildren();
        for (int i = 0; i < children.size(); i++) {
            final BoxData child = children.get(i);
            assertNotNull(child, "child " + i + " is null");
            assertEquals(boxData.getLevel(), child.getParentLevel(),
                         "invalid parent level for child " + i);
            assertEquals(boxData.getRow(), child.getParentRow(),
                         "invalid parent row for child " + i);
            assertEquals(boxData.getColumn(), child.getParentColumn(),
                         "invalid parent column for child " + i);
        }

        boxData = BoxData.fromString("0,99,10,20,1,");

        assertEquals(0, boxData.getLevel(), "invalid level parsed");
        assertEquals(0, boxData.getChildCount(), "invalid number of children parsed");

        boxData = BoxData.fromString("2,99,40,41,1,13");

        assertEquals(2, boxData.getLevel(), "invalid level parsed");
        assertEquals(2, boxData.getChildCount(), "invalid number of children parsed");

        children = boxData.getChildren();
        assertEquals(1, children.get(0).getParentIndex(), "invalid parent index for first child");
        assertEquals(3, children.get(1).getParentIndex(), "invalid parent index for second child");

    }

}