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

import java.util.HashMap;
import java.util.Map;

/**
 * Simple cache of mask byte processor instances.
 * This was created to help performance of processing a single request with many tiles that use the same mask.
 * This is not thread safe and one instance should not be shared by different requests!
 * If we decide a shared instance is worthwhile, this cache should be re-implemented as a thread-safe LRU cache.
 *
 * @author Eric Trautman
 */
public class ByteProcessorCache {

    private Map<String, ByteProcessor> urlToProcessorMap;

    public ByteProcessorCache() {
        this.urlToProcessorMap = new HashMap<String, ByteProcessor>();
    }

    public ByteProcessor getProcessor(String urlString,
                                      int mipmapLevel) {
        final String key = getCacheKey(urlString, mipmapLevel);
        ByteProcessor processor = urlToProcessorMap.get(key);
        if (processor ==  null) {
            processor = buildByteProcessor(urlString, mipmapLevel);
            if (processor != null) {
                urlToProcessorMap.put(key, processor);
            }
        }
        return processor;
    }

    private ByteProcessor buildByteProcessor(String urlString,
                                             int mipmapLevel) {
        ByteProcessor byteProcessor = null;
        if (urlString != null) {
            final ImagePlus imagePlus = Utils.openImagePlusUrl(urlString);
            if (imagePlus == null) {
                LOG.error("buildByteProcessor: failed to load image plus for '" + urlString + "'.");
            } else {
                final ImageProcessor imageProcessor = imagePlus.getProcessor();
                byteProcessor = Downsampler.downsampleByteProcessor(imageProcessor.convertToByteProcessor(),
                                                                    mipmapLevel);
            }
        }
        return byteProcessor;
    }

    private String getCacheKey(String urlString,
                               int mipmapLevel) {
        return urlString + '|' + mipmapLevel; // hack! but good enough for now ...
    }

    private static final Logger LOG = LoggerFactory.getLogger(Render.class);
}
