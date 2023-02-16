package org.janelia.alignment.spec;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class ChannelSpec implements Serializable {

    private final String name;
    private Double minIntensity;
    private Double maxIntensity;
    private final TreeMap<Integer, ImageAndMask> mipmapLevels;
    private MipmapPathBuilder mipmapPathBuilder;

    private FilterSpec filterSpec;

    public ChannelSpec() {
        this(null, null);
    }

    public ChannelSpec(final Double minIntensity,
                       final Double maxIntensity) {
        this(null, minIntensity, maxIntensity, new TreeMap<>(), null, null);
    }

    public ChannelSpec(final String name,
                       final Double minIntensity,
                       final Double maxIntensity) {
        this(name, maxIntensity, minIntensity, new TreeMap<>(), null, null);
    }

    public ChannelSpec(final String name,
                       final Double minIntensity,
                       final Double maxIntensity,
                       final TreeMap<Integer, ImageAndMask> mipmapLevels,
                       final MipmapPathBuilder mipmapPathBuilder,
                       final FilterSpec filterSpec) {
        this.name = name;
        this.minIntensity = minIntensity == null ? 0 : minIntensity;
        this.maxIntensity = maxIntensity == null ? 255 : maxIntensity;
        this.mipmapLevels = mipmapLevels;
        this.mipmapPathBuilder = mipmapPathBuilder;
        this.filterSpec = filterSpec;
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

    void setMinAndMaxIntensity(final double minIntensity,
                               final double maxIntensity)  {
        this.minIntensity = minIntensity;
        this.maxIntensity = maxIntensity;
    }

    public boolean is16Bit() {
        return (maxIntensity > 255);
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return true if this tile spec is missing a mipmap for the specified level; otherwise false.
     */
    public boolean isMissingMipmap(final Integer level) {
        return ! mipmapLevels.containsKey(level);
    }

    /**
     * @return true if this channel has a mask.
     */
    public boolean hasMask() {
        final Map.Entry<Integer, ImageAndMask> firstMipmapEntry = getFirstMipmapEntry();
        return (firstMipmapEntry != null) && (firstMipmapEntry.getValue().hasMask());
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

    public String getContext(final String tileId) {
        final String context;
        if (name == null) {
            context = "tile '" + tileId + "'";
        } else {
            context = "channel '" + name + "' in tile '" + tileId + "'";
        }
        return context;
    }

    public ImageAndMask getFirstMipmapImageAndMask(final String tileId) throws IllegalArgumentException {
        final Map.Entry<Integer, ImageAndMask> firstEntry = getFirstMipmapEntry();
        if (firstEntry == null) {
            throw new IllegalArgumentException("first entry mipmap is missing from " + getContext(tileId));
        }

        final ImageAndMask imageAndMask = firstEntry.getValue();

        if ((imageAndMask == null) || (! imageAndMask.hasImage())) {
            throw new IllegalArgumentException("first entry mipmap image is missing from " + getContext(tileId));
        }

        return imageAndMask;
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

    public MipmapPathBuilder getMipmapPathBuilder() {
        return mipmapPathBuilder;
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

        mipmapLevels.values().forEach(ImageAndMask::validate);
    }

    /**
     * Note that an earlier implementation of this method intended to return a complete list of mipmap levels
     * but the implementation had a bug in it so the raw data was actually returned.
     * The buggy implementation was never used and I now need the raw data, so I've simplified the implementation.
     *
     * @return raw (possibly incomplete) mipmap levels.
     */
    public TreeMap<Integer, ImageAndMask> getMipmapLevels() {
        return mipmapLevels;
    }

    public FilterSpec getFilterSpec() {
        return filterSpec;
    }

    public boolean hasFilterSpec() {
        return filterSpec != null;
    }

    public void setFilterSpec(final FilterSpec filterSpec) {
        this.filterSpec = filterSpec;
    }
}
