package org.janelia.alignment.mipmap;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.trakem2.util.Downsampler;

/**
 * A {@link MipmapSource} implementation that contains explicitly set channels.
 *
 * @author Eric Trautman
 */
public class SimpleMipmapSource
        implements MipmapSource {

    private final String sourceName;
    private final Map<String, ImageProcessorWithMasks> channels;

    private final int fullScaleWidth;
    private final int fullScaleHeight;

    public SimpleMipmapSource(final String sourceName,
                              final Map<String, ImageProcessorWithMasks> channels) {
        this.sourceName = sourceName;
        this.channels = channels;

        final ImageProcessorWithMasks firstChannel = getFirstChannel(channels);
        this.fullScaleWidth = firstChannel.ip.getWidth();
        this.fullScaleHeight = firstChannel.ip.getHeight();
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public int getFullScaleWidth() {
        return fullScaleWidth;
    }

    @Override
    public int getFullScaleHeight() {
        return fullScaleHeight;
    }

    @Override
    public Map<String, ImageProcessorWithMasks> getChannels(final int mipmapLevel)
            throws IllegalArgumentException {

        Map<String, ImageProcessorWithMasks> channelsAtLevel = channels;

        if (mipmapLevel > 0) {

            channelsAtLevel = new HashMap<>();

            for (final String channelName : channels.keySet()) {

                final ImageProcessorWithMasks level0 = channels.get(channelName);

                final ImageProcessor downSampledSource = Downsampler.downsampleImageProcessor(level0.ip,
                                                                                              mipmapLevel);
                final ImageProcessor downSampledMask =
                        (level0.mask == null) ? null : Downsampler.downsampleImageProcessor(level0.mask,
                                                                                            mipmapLevel);
                final ByteProcessor downSampledOutside =
                        (level0.outside == null) ? null : Downsampler.downsampleByteProcessor(level0.outside,
                                                                                              mipmapLevel);

                channelsAtLevel.put(channelName, new ImageProcessorWithMasks(downSampledSource,
                                                                             downSampledMask,
                                                                             downSampledOutside));
            }

        }

        return channelsAtLevel;
    }


    public static ImageProcessorWithMasks getFirstChannel(final Map<String, ImageProcessorWithMasks> channels) {
        return getFirstChannel(channels.values());
    }

    public static ImageProcessorWithMasks getFirstChannel(final Collection<ImageProcessorWithMasks> channelList) {
        ImageProcessorWithMasks firstChannel = null;
        //noinspection LoopStatementThatDoesntLoop
        for (final ImageProcessorWithMasks channel : channelList) {
            firstChannel = channel;
            break;
        }
        return firstChannel;
    }

}
