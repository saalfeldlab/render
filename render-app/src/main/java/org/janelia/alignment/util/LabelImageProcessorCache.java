package org.janelia.alignment.util;

import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This cache overrides the standard load implementation by replacing real tile pixels with pixels
 * of a single label color.  Each tile receives a distinct label color.  This labelling feature was
 * introduced to support processing using Michael Kazhdan's Distributed Gradient-Domain Processing
 * of Planar and Spherical Images approach (see
 *
 * <a href="http://www.cs.jhu.edu/~misha/Code/DMG/Version3.11/">
 *     http://www.cs.jhu.edu/~misha/Code/DMG/Version3.11/
 * </a>).
 *
 * Although the approach does not require it, a naive attempt is made to distribute assigned label
 * colors so that adjacent tiles are less likely to be assigned similar label colors.
 * This distribution is now consistent across runs so that failed runs can be resumed instead of
 * needing to be restarted from scratch.
 *
 * @author Eric Trautman
 */
public class LabelImageProcessorCache extends ImageProcessorCache {

    /*
     * @return a list of consistently shuffled RGB colors suitable for use as
     *         16-bit gray colors (red = 0).
     */
    public static List<Color> buildColorList() {
        final int maxComponentCount = 256 - 2; // exclude 0 and 255
        final List<Color> colorList = new ArrayList<>(maxComponentCount * maxComponentCount);
        for (int green = 1; green < maxComponentCount; green++) {
            for (int blue = 1; blue < maxComponentCount; blue++) {
                // only use low order (green and blue) bytes for RGB colors
                // so that no data is lost during 16-bit gray conversion;
                colorList.add(new Color(0, green, blue));
            }
        }

        // use same seed to shuffle consistently, 99 seems to distribute colors well enough
        final Random consistentShuffler = new Random(99);

        Collections.shuffle(colorList, consistentShuffler);

        return colorList;
    }

    private final Map<String, Color> urlToColor;
    private final Map<String, TileSpec> urlToTileSpec;

    /**
     * Constructs a cache instance using the specified parameters.
     *
     * @param  maximumNumberOfCachedPixels         the maximum number of pixels to maintain in the cache.
     *                                             This should roughly correlate to the maximum amount of
     *                                             memory for the cache.
     *
     * @param  recordStats                         if true, useful tuning stats like cache hits and loads will be
     *                                             maintained (presumably at some nominal overhead cost);
     *                                             otherwise stats are not maintained.
     *
     * @param  cacheOriginalsForDownSampledImages  if true, when down sampled images are requested their source
     *                                             images will also be cached (presumably improving the speed
     *                                             of future down sampling to a different level);
     *                                             otherwise only the down sampled result images are cached.
     *
     * @param  tileSpecs                           collection of all tileSpecs that may be loaded from this cache.
     *                                             The collection is used to consistently assign label colors to each tile
     *                                             across runs (assuming each run uses the same collection of tiles
     *                                             in the same order).
     */
    public LabelImageProcessorCache(final long maximumNumberOfCachedPixels,
                                    final boolean recordStats,
                                    final boolean cacheOriginalsForDownSampledImages,
                                    final Collection<TileSpec> tileSpecs) {

        super(maximumNumberOfCachedPixels, recordStats, cacheOriginalsForDownSampledImages);

        final int initialCapacity = tileSpecs.size() * 2;
        this.urlToColor = new HashMap<>(initialCapacity);
        this.urlToTileSpec = new HashMap<>(initialCapacity);

        buildMaps(tileSpecs);
    }

    protected Color getColorForUrl(final String url)
            throws IllegalArgumentException {

        final Color labelColor = urlToColor.get(url);

        if (labelColor == null) {
            throw new IllegalArgumentException("no label color defined for " + url);
        }

        return labelColor;
    }

    /**
     * Loads a label image processor when cache misses occur for source images.
     * Masks are loaded in the standard manner.
     *
     * @param  url               url for the image.
     * @param  downSampleLevels  number of levels to further down sample the image.
     * @param  isMask            indicates whether this image is a mask.
     * @param  convertTo16Bit    ignored for labels (always false).
     *
     * @return a newly loaded image processor to be cached.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    @Override
    protected ImageProcessor loadImageProcessor(final String url,
                                                final int downSampleLevels,
                                                final boolean isMask,
                                                final boolean convertTo16Bit)
            throws IllegalArgumentException {

        ImageProcessor imageProcessor;

        if (isMask) {
            imageProcessor = super.loadImageProcessor(url, downSampleLevels, true, convertTo16Bit);
        } else {

            final Color labelColor = getColorForUrl(url);

            if (LOG.isDebugEnabled()) {
                LOG.debug("loadImageProcessor: loading label, url={}, downSampleLevels={}, color={}",
                          url, downSampleLevels, labelColor);
            }

            final TileSpec tileSpec = urlToTileSpec.get(url);
            imageProcessor = loadLabelProcessor(tileSpec.getWidth(), tileSpec.getHeight(), labelColor);

            // down sample the image as needed
            if (downSampleLevels > 0) {
                // NOTE: The down sample methods return a safe copy and leave the source imageProcessor unmodified,
                //       so we don't need to duplicate a cached source instance before down sampling.
                imageProcessor = Downsampler.downsampleImageProcessor(imageProcessor,
                                                                      downSampleLevels);
            }

        }

        return imageProcessor;
    }

    private ImageProcessor loadLabelProcessor(final int width,
                                              final int height,
                                              final Color color)
            throws IllegalArgumentException {

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2d = image.createGraphics();
        g2d.drawImage(image, 0, 0, color, null);
        return new ColorProcessor(image);

    }

    private void buildMaps(final Collection<TileSpec> tileSpecs) {

        final List<Color> colorList = buildColorList();

        if (tileSpecs.size() > colorList.size()) {
            throw new IllegalArgumentException(
                    tileSpecs.size() + " tile specs were specified but color model can only support a maximum of " +
                    colorList.size() + " distinct labels");
        }

        int tileIndex = 0;
        ChannelSpec firstChannelSpec;
        String imageUrl;
        for (final TileSpec tileSpec : tileSpecs) {
            firstChannelSpec = tileSpec.getAllChannels().get(0);
            imageUrl = firstChannelSpec.getFloorMipmapEntry(0).getValue().getImageUrl();
            urlToTileSpec.put(imageUrl, tileSpec);
            urlToColor.put(imageUrl, colorList.get(tileIndex));
            tileIndex++;
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(LabelImageProcessorCache.class);

}
