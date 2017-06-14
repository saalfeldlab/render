package org.janelia.alignment.mipmap;

import ij.process.ImageProcessor;

import java.util.List;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ChannelMap;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MipmapSource} implementation that loads pixel data from image and
 * mask URLs (files/resources) identified in {@link ChannelSpec channel specs}.
 *
 * @author Eric Trautman
 */
public class UrlMipmapSource
        implements MipmapSource {

    private final String sourceName;
    private int fullScaleWidth;
    private int fullScaleHeight;
    private final List<ChannelSpec> channelSpecList;
    private final Double renderMinIntensity;
    private final Double renderMaxIntensity;
    private final boolean excludeMask;
    private final ImageProcessorCache imageProcessorCache;

    /**
     * Constructs a source that will load data when {@link #getChannels} is called.
     *
     * @param  sourceName           name of this source.
     * @param  fullScaleWidth       full scale (level 0) width of this source (specify as -1 if unknown).
     * @param  fullScaleHeight      full scale (level 0) height of this source (specify as -1 if unknown).
     * @param  channelSpecList      list of channel specifications for this source.
     * @param  renderMinIntensity   minimum intensity value for all channel specs (or null to use spec intensity values).
     * @param  renderMaxIntensity   maximum intensity value for all channel specs (or null to use spec intensity values).
     * @param  excludeMask          flag indicating whether mask data should be excluded.
     * @param  imageProcessorCache  cache of previously loaded pixel data (or null if caching is not desired).
     */
    public UrlMipmapSource(final String sourceName,
                           final int fullScaleWidth,
                           final int fullScaleHeight,
                           final List<ChannelSpec> channelSpecList,
                           final Double renderMinIntensity,
                           final Double renderMaxIntensity,
                           final boolean excludeMask,
                           final ImageProcessorCache imageProcessorCache) {
        this.sourceName = sourceName;
        this.fullScaleWidth = fullScaleWidth;
        this.fullScaleHeight = fullScaleHeight;
        this.channelSpecList = channelSpecList;
        this.renderMinIntensity = renderMinIntensity;
        this.renderMaxIntensity = renderMaxIntensity;
        this.excludeMask = excludeMask;
        if (imageProcessorCache == null) {
            this.imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;
        } else {
            this.imageProcessorCache = imageProcessorCache;
        }
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public int getFullScaleWidth() {
        if (fullScaleWidth == -1) {
            setWidthAndHeight();
        }
        return fullScaleWidth;
    }

    @Override
    public int getFullScaleHeight() {
        if (fullScaleHeight == -1) {
            setWidthAndHeight();
        }
        return fullScaleHeight;
    }

    @Override
    public ChannelMap getChannels(final int mipmapLevel)
            throws IllegalArgumentException {

        final ChannelMap channels = new ChannelMap();

        if ((channelSpecList != null) && (channelSpecList.size() > 0)) {

            final long loadMipStart = System.currentTimeMillis();

            final ChannelSpec firstChannelSpec = channelSpecList.get(0);

            int downSampleLevels = 0;

            final Map.Entry<Integer, ImageAndMask> mipmapEntry = firstChannelSpec.getFloorMipmapEntry(mipmapLevel);
            final ImageAndMask imageAndMask = mipmapEntry.getValue();

            final int currentMipmapLevel = mipmapEntry.getKey();
            if (currentMipmapLevel < mipmapLevel) {
                downSampleLevels = mipmapLevel - currentMipmapLevel;
            }

            final ImageProcessor imageProcessor = imageProcessorCache.get(imageAndMask.getImageUrl(),
                                                                          downSampleLevels,
                                                                          false,
                                                                          firstChannelSpec.is16Bit());
            final long loadMipStop = System.currentTimeMillis();

            if (imageProcessor.getWidth() == 0 || imageProcessor.getHeight() == 0) {

                LOG.debug("skipping " + getSourceName() + " mipmap " + imageAndMask.getImageUrl() +
                          " with zero dimension after down-sampling " + downSampleLevels + " levels");

            } else {

                // open mask
                final ImageProcessor maskProcessor;
                final String maskUrl = imageAndMask.getMaskUrl();
                if ((maskUrl != null) && (!excludeMask)) {
                    maskProcessor = imageProcessorCache.get(maskUrl, downSampleLevels, true, false);
                } else {
                    maskProcessor = null;
                }

                final long loadMaskStop = System.currentTimeMillis();

                setMinAndMaxIntensity(imageProcessor, firstChannelSpec);

                final ImageProcessorWithMasks firstChannel =
                        new ImageProcessorWithMasks(imageProcessor, maskProcessor, null);

                // log warning if source.mask gets "quietly" removed (because of size)
                if ((maskProcessor != null) && (firstChannel.mask == null)) {
                    LOG.warn("getChannels: {} mask removed because image {} size ({}x{}) differs from mask {} size ({}x{})",
                             sourceName,
                             imageAndMask.getImageUrl(), imageProcessor.getWidth(), imageProcessor.getHeight(),
                             imageAndMask.getMaskUrl(), maskProcessor.getWidth(), maskProcessor.getHeight());
                }

                channels.put(firstChannelSpec.getName(), firstChannel);

                if (channelSpecList.size() > 1) {
                    final boolean firstChannelHasMask = (firstChannel.mask != null);
                    loadAdditionalChannels(imageProcessor.getWidth(),
                                           imageProcessor.getHeight(),
                                           firstChannelHasMask,
                                           mipmapLevel,
                                           channels);
                }

                final long loadAdditionalChannelsStop = System.currentTimeMillis();

                LOG.debug("getChannels: {} took {} milliseconds to load level {} (first mip:{}, downSampleLevels:{}, first mask:{}, additional channels:{}), cacheSize:{}",
                          sourceName,
                          loadAdditionalChannelsStop - loadMipStart,
                          mipmapLevel,
                          loadMipStop - loadMipStart,
                          downSampleLevels,
                          loadMaskStop - loadMipStop,
                          loadAdditionalChannelsStop - loadMaskStop,
                          imageProcessorCache.size());
            }
        }

        return channels;
    }

    /**
     * Loads remaining channel data for multi-channel images.
     *
     * @param  firstChannelWidth    first channel width (at requested mipmap level).
     * @param  firstChannelHeight   first channel height (at requested mipmap level).
     * @param  firstChannelHasMask  indicates whether the first channel has a mask.
     * @param  mipmapLevel          requested mipmap level for all channels.
     * @param  channels             map of pixel data for all source channels.
     */
    private void loadAdditionalChannels(final int firstChannelWidth,
                                        final int firstChannelHeight,
                                        final boolean firstChannelHasMask,
                                        final int mipmapLevel,
                                        final ChannelMap channels) {

        for (int i = 1; i < channelSpecList.size(); i++) {

            final ChannelSpec channelSpec = channelSpecList.get(i);
            final Map.Entry<Integer, ImageAndMask> mipmapEntry =
                    channelSpec.getFloorMipmapEntry(mipmapLevel);
            final ImageAndMask imageAndMask = mipmapEntry.getValue();

            int downSampleLevels = 0;
            final int currentMipmapLevel = mipmapEntry.getKey();
            if (currentMipmapLevel < mipmapLevel) {
                downSampleLevels = mipmapLevel - currentMipmapLevel;
            }

            final ImageProcessor imageProcessor = imageProcessorCache.get(imageAndMask.getImageUrl(),
                                                                          downSampleLevels,
                                                                          false,
                                                                          channelSpec.is16Bit());

            if (imageProcessor.getWidth() == firstChannelWidth && imageProcessor.getWidth() == firstChannelHeight) {

                // open mask
                final ImageProcessor maskProcessor;
                final String maskUrl = imageAndMask.getMaskUrl();
                if ((maskUrl != null) && (! excludeMask)) {
                    maskProcessor = imageProcessorCache.get(maskUrl, downSampleLevels, true, false);
                } else {
                    maskProcessor = null;
                }

                setMinAndMaxIntensity(imageProcessor, channelSpec);

                final ImageProcessorWithMasks channel = new ImageProcessorWithMasks(imageProcessor,
                                                                                    maskProcessor,
                                                                                    null);
                channels.put(channelSpec.getName(), channel);

            } else {

                LOG.warn("loadAdditionalChannels: skipping {} channel {} mipmap {} because level {} dimensions ({}x{}) differ from primary channel ({}x{})",
                         getSourceName(), channelSpec.getName(), imageAndMask.getImageUrl(),
                         mipmapLevel, imageProcessor.getWidth(), imageProcessor.getHeight(),
                         firstChannelWidth, firstChannelHeight);
            }
        }

        // TODO: do we want to allow heterogeneous mask situations? pixel mappers currently expect homogeneous masks

        // clean-up heterogeneous mask situations
        boolean removeAllMasks = false;
        for (final ImageProcessorWithMasks channel : channels.values()) {
            if (firstChannelHasMask) {
                if (channel.mask == null) {
                    removeAllMasks = true;
                    break;
                }
            } else if (channel.mask != null) {
                removeAllMasks = true;
                break;
            }
        }

        if (removeAllMasks) {
            LOG.warn("removing masks for {} because some channels have masks and others do not", getSourceName());
            for (final ImageProcessorWithMasks channel : channels.values()) {
                channel.mask = null;
            }
        }

    }

    /**
     * Derives the full scale (level 0) width and height of this source's first channel.
     */
    private void setWidthAndHeight() {
        final ChannelSpec firstChannelSpec = channelSpecList.get(0);
        final Map.Entry<Integer, ImageAndMask> mipmapEntry = firstChannelSpec.getFloorMipmapEntry(0);
        final ImageAndMask imageAndMask = mipmapEntry.getValue();
        final ImageProcessor imageProcessor = imageProcessorCache.get(imageAndMask.getImageUrl(),
                                                                      0,
                                                                      false,
                                                                      firstChannelSpec.is16Bit());
        fullScaleWidth = imageProcessor.getWidth();
        fullScaleHeight = imageProcessor.getHeight();
    }

    private void setMinAndMaxIntensity(final ImageProcessor imageProcessor,
                                       final ChannelSpec channelSpec) {
        final double minChannelIntensity = (renderMinIntensity == null) ? channelSpec.getMinIntensity() : renderMinIntensity;
        final double maxChannelIntensity = (renderMaxIntensity == null) ? channelSpec.getMaxIntensity() : renderMaxIntensity;
        imageProcessor.setMinAndMax(minChannelIntensity, maxChannelIntensity);
    }

    private static final Logger LOG = LoggerFactory.getLogger(UrlMipmapSource.class);

}
