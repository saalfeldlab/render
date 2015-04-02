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
package org.janelia.alignment.canvas;

import ij.process.ImageProcessor;

import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.canvas.AlphaIntensityImageProcessors.AlphaIntensityChannel;
import org.janelia.alignment.util.ImageProcessorCache;

/**
 * TODO Do it!  Caching should be on per-file basis through a global cache such that reoccurring masks can be re-used.  Otherwise no distinction between channels and alpha.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class AlphaIntensityImageProcessorMipmaps implements MultiChannelMipmaps<AlphaIntensityChannel, ImageProcessor> {

    final protected int width;
    final protected int height;

    final static protected ImageProcessorCache defaultCache = new ImageProcessorCache(
            2000000000,
            false,
            false);

    final transient protected ImageProcessorCache cache;
    final protected TreeMap<Integer, ImageAndMask> mipmapLevelUrls;

    public AlphaIntensityImageProcessorMipmaps(
            final int width,
            final int height,
            final TreeMap<Integer, ImageAndMask> mipmapLevelUrls,
            final ImageProcessorCache cache) {
        this.width = width;
        this.height = height;
        this.mipmapLevelUrls = mipmapLevelUrls;
        this.cache = cache;
    }

    public AlphaIntensityImageProcessorMipmaps(
            final int width,
            final int height,
            final int[] levels,
            final String[][] imageMaskUrls,
            final ImageProcessorCache cache) {
        this(width, height, new TreeMap<Integer, ImageAndMask>(), cache);

        assert levels.length == imageMaskUrls.length : "Level index list does not match string list.";

        for (int i = 0; i < levels.length; ++i)
            mipmapLevelUrls.put(levels[i], new ImageAndMask(imageMaskUrls[i][0], imageMaskUrls[i].length > 1 ? imageMaskUrls[i][1] : null));
    }

    protected Map.Entry<Integer, ImageAndMask> getFloorMipmapEntry(final Integer mipmapLevel) {
        Map.Entry<Integer, ImageAndMask> floorEntry = mipmapLevelUrls.floorEntry(mipmapLevel);
        if (floorEntry == null) {
            floorEntry = mipmapLevelUrls.firstEntry();
        }
        return floorEntry;
    }

    protected ImageProcessor getMipmapLevel(final int mipmapLevel) {

        int downSampleLevels = 0;

        final Map.Entry<Integer, ImageAndMask> mipmapEntry = getFloorMipmapEntry(mipmapLevel);
        final ImageAndMask imageAndMask = mipmapEntry.getValue();

        final int currentMipmapLevel = mipmapEntry.getKey();
        if (currentMipmapLevel < mipmapLevel) {
            downSampleLevels = mipmapLevel - currentMipmapLevel;
        }

        return cache.get(imageAndMask.getImageUrl(), downSampleLevels, false, false);
    }

    @Override
    public void bounds(final double[] min, final double[] max) {
        // TODO Auto-generated method stub

    }

    @Override
    public MultiChannelScaleLevel<AlphaIntensityChannel, ImageProcessor> getScaleLevel(final int scaleLevelIndex) {
        // TODO Auto-generated method stub
        return null;
    }


}
