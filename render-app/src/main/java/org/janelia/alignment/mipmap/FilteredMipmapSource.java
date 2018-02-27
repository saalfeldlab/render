package org.janelia.alignment.mipmap;

import java.util.List;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ChannelMap;
import org.janelia.alignment.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MipmapSource} implementation that filters the pixels of
 * another {@link MipmapSource} using a list of {@link Filter filters}.
 *
 * @author Eric Trautman
 */
public class FilteredMipmapSource
        implements MipmapSource {

    private final String sourceName;
    private final MipmapSource source;
    private final List<Filter> filterList;

    public FilteredMipmapSource(final String sourceName,
                                final MipmapSource source,
                                final List<Filter> filterList) {
        this.sourceName = sourceName;
        this.source = source;
        this.filterList = filterList;
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public int getFullScaleWidth() {
        return source.getFullScaleWidth();
    }

    @Override
    public int getFullScaleHeight() {
        return source.getFullScaleHeight();
    }

    @Override
    public ChannelMap getChannels(final int mipmapLevel)
            throws IllegalArgumentException {

        // TODO: filtering changes the source pixels (in-place) making multiple calls for the same level unsafe, is that okay?

        final double mipmapScale = 1.0 / (1 << mipmapLevel);

        final ChannelMap channels = source.getChannels(mipmapLevel);

        final long filterStart = System.currentTimeMillis();

        for (final ImageProcessorWithMasks channel : channels.values()) {
            for (final Filter filter : filterList) {
                filter.process(channel.ip, mipmapScale);
            }
        }

        final long filterStop = System.currentTimeMillis();

        LOG.debug("getChannels: {} took {} milliseconds to filter level {}",
                  getSourceName(),
                  filterStop - filterStart,
                  mipmapLevel);

        return channels;
    }

    private static final Logger LOG = LoggerFactory.getLogger(FilteredMipmapSource.class);

}
