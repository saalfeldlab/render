package org.janelia.render.client.newsolver.solvers.intensity;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.janelia.alignment.util.ImageProcessorUtil;

/**
 * The pair-independent, cacheable result of loading, filtering and downsampling a tile: the downsampled source image,
 * its downsampled alpha mask (or {@code null}), and the mipmap level they were downsampled to. Treated as read-only
 * once cached, so it can be shared across the tile's overlap pairs and match threads.
 */
record DownsampledSource(ImageProcessor image, ByteProcessor mask, int mipmapLevel) {

    long kilobytes() {
        return ImageProcessorUtil.getKilobytes(image) + (mask == null ? 0 : ImageProcessorUtil.getKilobytes(mask));
    }
}
