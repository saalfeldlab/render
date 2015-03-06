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

import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Map;
import java.util.TreeMap;

import mpicbg.models.IdentityModel;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.util.ImageProcessorCache;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Mipmaps extends AbstractCanvas<IdentityModel> {

    final static protected IdentityModel t = new IdentityModel();

    final static protected ImageProcessorCache defaultCache = new ImageProcessorCache(
            2000000000,
            false,
            false);

    final transient protected ImageProcessorCache cache;
    final protected TreeMap<Integer, ImageAndMask> mipmapLevelUrls;

    public Mipmaps(
            final double width,
            final double height,
            final TreeMap<Integer, ImageAndMask> mipmapLevelUrls,
            final ImageProcessorCache cache) {
        super(width, height);
        this.mipmapLevelUrls = mipmapLevelUrls;
        this.cache = cache;
    }

    public Mipmaps(
            final double width,
            final double height,
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

    protected ImageProcessor getMipmapLevelMask(final int mipmapLevel) {

        int downSampleLevels = 0;

        final Map.Entry<Integer, ImageAndMask> mipmapEntry = getFloorMipmapEntry(mipmapLevel);
        final ImageAndMask imageAndMask = mipmapEntry.getValue();

        final int currentMipmapLevel = mipmapEntry.getKey();
        if (currentMipmapLevel < mipmapLevel) {
            downSampleLevels = mipmapLevel - currentMipmapLevel;
        }

        return cache.get(imageAndMask.getImageUrl(), downSampleLevels, true, false);
    }

    @Override
    public void renderMask(final ByteProcessor bp, final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        bp.copyBits(getMipmapLevelMask(mipmapLevel), 0, 0, Blitter.ADD);
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        return getMipmapLevelMask(mipmapLevel).convertToByteProcessor();
    }

    @Override
    public void renderARGB(final BufferedImage image, final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // convert to 24bit RGB
        tp.setMinAndMax(ts.getMinIntensity(), ts.getMaxIntensity());
        final ColorProcessor cp = tp.convertToColorProcessor();

        final int[] cpPixels = (int[]) cp.getPixels();
        final byte[] alphaPixels;

        // set alpha channel
        if (maskTargetProcessor != null) {
            alphaPixels = (byte[]) maskTargetProcessor.getPixels();
        } else {
            alphaPixels = (byte[]) target.outside.getPixels();
        }

        for (int i = 0; i < cpPixels.length; ++i) {
            cpPixels[i] &= 0x00ffffff | (alphaPixels[i] << 24);
        }

        final BufferedImage image = new BufferedImage(cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, cp.getWidth(), cp.getHeight(), cpPixels);
    }

    @Override
    public Image getARGB(final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        final ColorProcessor ip = getMipmapLevel(mipmapLevel).convertToColorProcessor();
        final ByteProcessor alpha = getMipmapLevelMask(mipmapLevel);
        final int[] ipPixels = (int[])ip.getPixels();
        for (int i = 0; i < ip.getPixelCount(); ++i) {
            cpPixels[i] &= 0x00ffffff | (alphaPixels[i] << 24);
        }

        final BufferedImage image = new BufferedImage(cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, cp.getWidth(), cp.getHeight(), cpPixels);
        return getMipmapLevel(mipmapLevel).getBufferedImage();
    }

    @Override
    public void renderFloatChannel(final FloatProcessor fp, final int channel, final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub

    }

    @Override
    public FloatProcessor getFloatChannel(final int channel, final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public IdentityModel getTransform() {
        return t;
    }
}
