package org.janelia.alignment.mipmap;

import java.util.Arrays;
import java.util.List;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ChannelMap;
import org.janelia.alignment.filter.Filter;
import org.janelia.alignment.filter.NormalizeLocalContrast;
import org.janelia.alignment.filter.ValueToNoise;
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

    public static List<Filter> getDefaultFilters() {
        return DEFAULT_FILTERS;
    }

    private static final Logger LOG = LoggerFactory.getLogger(FilteredMipmapSource.class);

    // TODO: this is an ad-hoc filter bank for temporary use in alignment
    private static final ValueToNoise vtnf1 = new ValueToNoise(0, 64, 191);
    private static final ValueToNoise vtnf2 = new ValueToNoise(255, 64, 191);
    private static final NormalizeLocalContrast nlcf = new NormalizeLocalContrast(500, 500, 3, true, true);
//    private static final CLAHE clahe = new CLAHE(true, 250, 256, 2);

    private static final List<Filter> DEFAULT_FILTERS = Arrays.asList(vtnf1, vtnf2, nlcf);

}
