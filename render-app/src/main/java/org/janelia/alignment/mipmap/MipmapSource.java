package org.janelia.alignment.mipmap;

import java.io.Serializable;

import org.janelia.alignment.ChannelMap;

/**
 * Common interface for multi-channel image sources.
 *
 * @author Eric Trautman
 */
public interface MipmapSource extends Serializable {

    /**
     * @return name of image source.
     */
    String getSourceName();

    /**
     * @return pixel width of image source at full scale (level 0).
     */
    int getFullScaleWidth();

    /**
     * @return pixel height of image source at full scale (level 0).
     */
    int getFullScaleHeight();

    /**
     * @param  mipmapLevel  level in a power of 2 scale pyramid.
     *
     * @return map of channel names to pixel data for the specified mipmap level.
     *
     * @throws IllegalArgumentException
     *   if channels for the specified level cannot be loaded or retrieved.
     */
    ChannelMap getChannels(final int mipmapLevel)
            throws IllegalArgumentException;

}
