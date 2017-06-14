package org.janelia.alignment.spec;

import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class ChannelSpec {

    private final String name;
    private final Double minIntensity;
    private final Double maxIntensity;
    private final TreeMap<Integer, ImageAndMask> mipmapLevels;
    private MipmapPathBuilder mipmapPathBuilder;

    public ChannelSpec() {
        this(null, null);
    }

    public ChannelSpec(final Double minIntensity,
                       final Double maxIntensity) {
        this(null, maxIntensity, minIntensity, new TreeMap<Integer, ImageAndMask>(), null);
    }

    public ChannelSpec(final String name,
                       final Double minIntensity,
                       final Double maxIntensity,
                       final TreeMap<Integer, ImageAndMask> mipmapLevels,
                       final MipmapPathBuilder mipmapPathBuilder) {
        this.name = name;
        this.minIntensity = minIntensity == null ? 0 : minIntensity;
        this.maxIntensity = maxIntensity == null ? 255 : maxIntensity;
        this.mipmapLevels = mipmapLevels;
        this.mipmapPathBuilder = mipmapPathBuilder;
    }

    public String getName() {
        return name;
    }

    public double getMinIntensity() {
        return minIntensity;
    }

    public double getMaxIntensity() {
        return maxIntensity;
    }

    public boolean is16Bit() {
        return (maxIntensity > 255);
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return true if this tile spec contains mipmap for the specified level; otherwise false.
     */
    public boolean hasMipmap(final Integer level) {
        return mipmapLevels.containsKey(level);
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return the mipmap for the specified level or null if none exists.
     */
    public ImageAndMask getMipmap(final Integer level) {
        return mipmapLevels.get(level);
    }

    public void putMipmap(final Integer level,
                          final ImageAndMask value) {
        this.mipmapLevels.put(level, value);
    }

    public Map.Entry<Integer, ImageAndMask> getFirstMipmapEntry() {
        return mipmapLevels.firstEntry();
    }

    public Map.Entry<Integer, ImageAndMask> getFloorMipmapEntry(final Integer mipmapLevel) {
        return getFloorMipmapEntry(mipmapLevel, mipmapLevels);
    }

    public Map.Entry<Integer, ImageAndMask> getFloorMipmapEntry(final Integer mipmapLevel,
                                                                final TreeMap<Integer, ImageAndMask> levelToImageMap) {

        Map.Entry<Integer, ImageAndMask> floorEntry = levelToImageMap.floorEntry(mipmapLevel);

        if (floorEntry == null) {
            floorEntry = levelToImageMap.firstEntry();
        } else if ((floorEntry.getKey() < mipmapLevel) && (mipmapPathBuilder != null)) {
            floorEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel,
                                                              levelToImageMap.firstEntry(),
                                                              true);
        }

        return floorEntry;
    }

    public void setMipmapPathBuilder(final MipmapPathBuilder mipmapPathBuilder) {
        this.mipmapPathBuilder = mipmapPathBuilder;
    }

    /**
     * @throws IllegalArgumentException
     *   if this spec's mipmaps are invalid.
     */
    public void validateMipmaps(final String tileId) throws IllegalArgumentException {
        if (mipmapLevels.size() == 0) {
            final String context = name == null ? "tile '" : "channel '" + name + "' of tile '";
            throw new IllegalArgumentException(context + tileId + "' does not contain any mipmapLevel elements");
        }

        for (final ImageAndMask imageAndMask : mipmapLevels.values()) {
            imageAndMask.validate();
        }
    }

}
