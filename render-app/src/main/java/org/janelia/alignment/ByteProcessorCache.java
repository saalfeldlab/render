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
    private int lastMipmapLevel;
    private ByteProcessor lastByteProcessor;

    public ByteProcessorCache() {
        this.lastUrlString = null;
        this.lastMipmapLevel = 0;
        this.lastByteProcessor = null;
    }

    public ByteProcessor getProcessor(String urlString,
                                      int mipmapLevel,
                                      boolean isDownSamplingNeeded) {
        if ((lastByteProcessor == null) ||
            (! urlString.equals(lastUrlString)) ||
            (mipmapLevel != lastMipmapLevel)) {
            lastByteProcessor = buildByteProcessor(urlString, mipmapLevel, isDownSamplingNeeded);
            lastUrlString = urlString;
            lastMipmapLevel = mipmapLevel;
        }
        return lastByteProcessor;
    }

    private ByteProcessor buildByteProcessor(String urlString,
                                             int mipmapLevel,
                                             boolean isDownSamplingNeeded) {
        ByteProcessor byteProcessor = null;
        if (urlString != null) {
            final ImagePlus imagePlus = Utils.openImagePlusUrl(urlString);
            if (imagePlus == null) {
                LOG.error("buildByteProcessor: failed to load image plus for '{}'.", urlString);
            } else {
                final ImageProcessor imageProcessor = imagePlus.getProcessor();
                byteProcessor = imageProcessor.convertToByteProcessor();
                if (isDownSamplingNeeded) {
                    byteProcessor = Downsampler.downsampleByteProcessor(byteProcessor,
                                                                        mipmapLevel);
                }
            }
        }
        return byteProcessor;
    }

    private static final Logger LOG = LoggerFactory.getLogger(Render.class);
}
