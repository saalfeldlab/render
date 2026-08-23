package org.janelia.alignment.betterbox;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
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

        final BoxData firstBox = BoxData.fromString("1,2.3,4,5,2,0123");

        assertAll("invalid first box parsed",
                  () -> assertEquals(1, firstBox.getLevel(), "invalid level parsed"),
                  () -> assertEquals(2.3, firstBox.getZ(), 0.001, "invalid z parsed"),
                  () -> assertEquals(4, firstBox.getRow(), "invalid row parsed"),
                  () -> assertEquals(5, firstBox.getColumn(), "invalid column parsed"),
                  () -> assertEquals(2, firstBox.getNumberOfSiblings(), "invalid number of siblings parsed"),
                  () -> assertEquals(4, firstBox.getChildCount(), "invalid number of children parsed"));

        final List<BoxData> firstChildren = firstBox.getChildren();
        for (int i = 0; i < firstChildren.size(); i++) {
            final BoxData child = firstChildren.get(i);
            assertNotNull(child, "child " + i + " is null");
            assertEquals(firstBox.getLevel(), child.getParentLevel(),
                         "invalid parent level for child " + i);
            assertEquals(firstBox.getRow(), child.getParentRow(),
                         "invalid parent row for child " + i);
            assertEquals(firstBox.getColumn(), child.getParentColumn(),
                         "invalid parent column for child " + i);
        }

        final BoxData secondBox = BoxData.fromString("0,99,10,20,1,");

        assertEquals(0, secondBox.getLevel(), "invalid level parsed");
        assertEquals(0, secondBox.getChildCount(), "invalid number of children parsed");

        final BoxData thirdBox = BoxData.fromString("2,99,40,41,1,13");

        assertEquals(2, thirdBox.getLevel(), "invalid level parsed");
        assertEquals(2, thirdBox.getChildCount(), "invalid number of children parsed");

        final List<BoxData> thirdChildren = thirdBox.getChildren();
        assertEquals(1, thirdChildren.get(0).getParentIndex(), "invalid parent index for first child");
        assertEquals(3, thirdChildren.get(1).getParentIndex(), "invalid parent index for second child");

    }

}