package org.janelia.alignment.betterbox;

import java.util.List;

import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link BoxData} class.
 *
 * @author Eric Trautman
 */
public class BoxDataTest {

    @Test
    public void testFromString() throws Exception {

        BoxData boxData = BoxData.fromString("1,2.3,4,5,2,0123");

        Assert.assertEquals("invalid level parsed", 1, boxData.getLevel());
        Assert.assertEquals("invalid z parsed", 2.3, boxData.getZ(), 0.001);
        Assert.assertEquals("invalid row parsed", 4, boxData.getRow());
        Assert.assertEquals("invalid column parsed", 5, boxData.getColumn());
        Assert.assertEquals("invalid number of siblings parsed", 2, boxData.getNumberOfSiblings());
        Assert.assertEquals("invalid number of children parsed", 4, boxData.getChildCount());

        List<BoxData> children = boxData.getChildren();
        for (int i = 0; i < children.size(); i++) {
            final BoxData child = children.get(i);
            Assert.assertNotNull("child " + i + " is null", child);
            Assert.assertEquals("invalid parent level for child " + i,
                                boxData.getLevel(), child.getParentLevel());
            Assert.assertEquals("invalid parent row for child " + i,
                                boxData.getRow(), child.getParentRow());
            Assert.assertEquals("invalid parent column for child " + i,
                                boxData.getColumn(), child.getParentColumn());
        }

        boxData = BoxData.fromString("0,99,10,20,1,");

        Assert.assertEquals("invalid level parsed", 0, boxData.getLevel());
        Assert.assertEquals("invalid number of children parsed", 0, boxData.getChildCount());

        boxData = BoxData.fromString("2,99,40,41,1,13");

        Assert.assertEquals("invalid level parsed", 2, boxData.getLevel());
        Assert.assertEquals("invalid number of children parsed", 2, boxData.getChildCount());

        children = boxData.getChildren();
        Assert.assertEquals("invalid parent index for first child", 1, children.get(0).getParentIndex());
        Assert.assertEquals("invalid parent index for second child", 3, children.get(1).getParentIndex());

    }

}