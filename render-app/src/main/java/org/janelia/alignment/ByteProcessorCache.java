/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import mpicbg.trakem2.util.Downsampler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple cache of the last mask byte processor instance created.
 * This was created to help performance of processing a single request with many tiles that use the same mask.
 * This is not thread safe and one instance should not be shared by different requests!
 * If we decide a shared instance or caching of multiple mask processors is worthwhile,
 * this cache should be re-implemented as a thread-safe LRU cache
 * (see commit log for a more detailed discussion about implementation options).
 *
 * @author Eric Trautman
 */
public class ByteProcessorCache {

    private String lastUrlString;
    private Integer lastDownSampleLevels;
    private ByteProcessor lastByteProcessor;

    public ByteProcessorCache() {
        this.lastUrlString = null;
        this.lastDownSampleLevels = null;
        this.lastByteProcessor = null;
    }

    /**
     * @param  urlString         url or path for the mask.
     *
     * @param  downSampleLevels  the amount of down sampling needed
     *                           (delta between level of the mask and the desired level for rendering).
     *
     * @return a processor (possibly already cached) for the specified mask and down sampling factor.
     */
    public ByteProcessor getProcessor(final String urlString,
                                      final Integer downSampleLevels) {
        if ((lastByteProcessor == null) ||
            (! urlString.equals(lastUrlString)) ||
            ((downSampleLevels == null) && (lastDownSampleLevels != null)) ||
            ((downSampleLevels != null) && (! downSampleLevels.equals(lastDownSampleLevels)))) {

            lastByteProcessor = buildByteProcessor(urlString, downSampleLevels);
            lastUrlString = urlString;
            lastDownSampleLevels = downSampleLevels;
        }
        return lastByteProcessor;
    }

    private ByteProcessor buildByteProcessor(final String urlString,
                                             final Integer downSampleLevels) {
        ByteProcessor byteProcessor = null;
        if (urlString != null) {
            final ImagePlus imagePlus = Utils.openImagePlusUrl(urlString);
            if (imagePlus == null) {
                LOG.error("buildByteProcessor: failed to load image plus for '{}'.", urlString);
            } else {
                final ImageProcessor imageProcessor = imagePlus.getProcessor();
                byteProcessor = imageProcessor.convertToByteProcessor();
                if (downSampleLevels != null) {
                    byteProcessor = Downsampler.downsampleByteProcessor(byteProcessor,
                                                                        downSampleLevels);
                }
            }
        }
        return byteProcessor;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ByteProcessorCache.class);
}
