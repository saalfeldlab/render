package org.janelia.alignment;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Pixel data for an ordered set of named channels.
 *
 * @author Eric Trautman
 */
public class ChannelMap {

    private final Map<String, ImageProcessorWithMasks> nameToChannelMap;
    private String firstChannelName;

    public ChannelMap() {
        this.nameToChannelMap = new LinkedHashMap<>();
        this.firstChannelName = null;
    }

    public ChannelMap(final String firstChannelName,
                      final ImageProcessorWithMasks firstChannel) {
        this.nameToChannelMap = new LinkedHashMap<>();
        this.nameToChannelMap.put(firstChannelName, firstChannel);
        this.firstChannelName = firstChannelName;
    }

    public Set<String> names() {
        return nameToChannelMap.keySet();
    }

    public Collection<ImageProcessorWithMasks> values() {
        return nameToChannelMap.values();
    }

    public String getFirstChannelName() {
        return firstChannelName;
    }

    public ImageProcessorWithMasks getFirstChannel() {
        return get(firstChannelName);
    }

    public ImageProcessorWithMasks get(final String name) {
        return nameToChannelMap.get(name);
    }

    public void put(final String name,
                    final ImageProcessorWithMasks channel) {
        nameToChannelMap.put(name, channel);
        if (firstChannelName == null) {
            firstChannelName = name;
        }
    }

    public int size() {
        return nameToChannelMap.size();
    }

    @Override
    public String toString() {
        return String.valueOf(names());
    }
}
