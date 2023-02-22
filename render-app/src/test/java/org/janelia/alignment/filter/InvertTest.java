package org.janelia.alignment.filter;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;

/**
 * Tests the {@link Invert} class.
 *
 * @author Eric Trautman
 */
public class InvertTest {

    public static void main(final String[] args) {
        new ImageJ();
        final String boatsUrl = "https://imagej.nih.gov/ij/images/boats.gif";
        final ImagePlus expectedIp = IJ.openImage(boatsUrl);
        expectedIp.setTitle("expectedResult");
        expectedIp.show();
        IJ.run("Invert");

        final ImagePlus actualIp = IJ.openImage(boatsUrl);
        actualIp.setTitle("actualResult");
        new Invert().process(actualIp.getProcessor(), 1.0);
        actualIp.show();
    }
    
}
