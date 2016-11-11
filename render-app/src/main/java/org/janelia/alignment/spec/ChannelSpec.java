package org.janelia.alignment.spec;

import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class ChannelSpec {

    // TODO: do we need a channel order attribute?

    private Double minIntensity;
    private Double maxIntensity;
    private final TreeMap<Integer, ImageAndMask> mipmapLevels;

    public ChannelSpec() {
        this.mipmapLevels = new TreeMap<>();
    }

    public void setMinIntensity(final Double minIntensity) {
        this.minIntensity = minIntensity;
    }

    public double getMinIntensity() {
        double value = 0;
        if (minIntensity != null) {
            value = minIntensity;
        }
        return value;
    }

    public double getMaxIntensity() {
        double value = 255;
        if (maxIntensity != null) {
            value = maxIntensity;
        }
        return value;
    }

    public void setMaxIntensity(final Double maxIntensity) {
        this.maxIntensity = maxIntensity;
    }

    public TreeMap<Integer, ImageAndMask> getMipmapLevels() {
        return mipmapLevels;
    }
}
