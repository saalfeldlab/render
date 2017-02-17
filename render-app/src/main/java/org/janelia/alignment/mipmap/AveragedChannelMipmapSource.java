package org.janelia.alignment.mipmap;

import ij.process.ByteProcessor;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ChannelMap;
import org.janelia.alignment.spec.ChannelNamesAndWeights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MipmapSource} implementation that uses specified weights to average N source channels into 1 channel.
 *
 * @author Eric Trautman
 */
public class AveragedChannelMipmapSource
        implements MipmapSource {

    private final String averagedChannelName;
    private final MipmapSource source;
    private final ChannelNamesAndWeights channelNamesAndWeights;

    /**
     * Basic constructor.
     *
     * @param  averagedChannelName       name of the averaged channel.
     * @param  source                    source channels to average.
     * @param  channelNamesAndWeights    source channel names and averaging weights.
     *
     * @throws IllegalArgumentException
     *   if the sum of all weights is greater than 1.0.
     */
    public AveragedChannelMipmapSource(final String averagedChannelName,
                                       final MipmapSource source,
                                       final ChannelNamesAndWeights channelNamesAndWeights) {

        this.averagedChannelName = averagedChannelName;
        this.source = source;
        this.channelNamesAndWeights = channelNamesAndWeights;
    }

    @Override
    public String getSourceName() {
        return averagedChannelName;
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

        final long start = System.currentTimeMillis();

        final ChannelMap sourceChannels = source.getChannels(mipmapLevel);

        final ImageProcessorWithMasks firstSourceChannel = sourceChannels.getFirstChannel();
        final int width = firstSourceChannel.ip.getWidth();
        final int height = firstSourceChannel.ip.getHeight();
        final int pixelCount = width * height;

        final ImageProcessorWithMasks averagedChannel =
                    new ImageProcessorWithMasks(firstSourceChannel.ip.createProcessor(width, height),
                                                new ByteProcessor(width, height),
                                                null);

        for (final String channelName : channelNamesAndWeights.getNames()) {

            final ImageProcessorWithMasks sourceChannel = sourceChannels.get(channelName);

            if (sourceChannel != null) {

                final float sourceWeight = channelNamesAndWeights.getWeight(channelName).floatValue();

                for (int i = 0; i < pixelCount; i++) {
                    averageChannelAndAlphaPixels(sourceChannel, sourceWeight, averagedChannel, i);
                }
            }

        }

        final ChannelMap averagedChannelMap = new ChannelMap(averagedChannelName, averagedChannel);

        final long stop = System.currentTimeMillis();

        LOG.debug("getChannels: {} took {} milliseconds to average {} channels for level {}",
                  getSourceName(),
                  stop - start,
                  channelNamesAndWeights.size(),
                  mipmapLevel);

        return averagedChannelMap;
    }

    /**
     * Uses the specified weight (0 <= w <= 1) to average a source channel pixel and alpha pixel
     * into (aggregate) target channel and alpha pixels.
     *
     * The generic process for averaging pixels between two channels is:
     *
     * <pre>
     *
     *         ( x0 * a0 * w0 ) + ( x1 * a1 * w1 )
     *     x = -----------------------------------
     *              ( a0 * w0 ) + ( a1 * w1 )
     *
     *         ( a0 * w0 ) + ( a1 * w1 )
     *     a = -------------------------
     *                  w0 + w1
     *
     * </pre>
     *
     * Since results are aggregated in the target, this can be simplified to:
     *
     * <pre>
     *
     *     x = ( x0 * a0 * w0 ) + x1
     *
     *     a = ( a0 * w0 ) + a1
     *
     * </pre>
     */
    public static void averageChannelAndAlphaPixels(final ImageProcessorWithMasks source,
                                                    final float sourceWeight,
                                                    final ImageProcessorWithMasks target,
                                                    final int pixelIndex) {

        final float maxMaskIntensity = 255.0f; // assumes mask is ByteProcessor

        final float sourceAlpha;
        if (source.mask == null) {
            sourceAlpha = 1.0f;
        } else {
            sourceAlpha = source.mask.getf(pixelIndex) / maxMaskIntensity;
        }

        final float weightedSourceAlpha = sourceAlpha * sourceWeight;

        if (weightedSourceAlpha > 0.0) {

            final float weightedSourceIntensity = source.ip.getf(pixelIndex) * weightedSourceAlpha;

            final float intensity = weightedSourceIntensity + target.ip.getf(pixelIndex);
            final float alpha = weightedSourceAlpha + (target.mask.getf(pixelIndex) / maxMaskIntensity);

            target.ip.setf(pixelIndex, intensity);
            target.mask.setf(pixelIndex, (alpha * maxMaskIntensity));

        } // else weightedSourceAlpha is 0, so nothing needs to change in the target
    }

    private static final Logger LOG = LoggerFactory.getLogger(AveragedChannelMipmapSource.class);

}
