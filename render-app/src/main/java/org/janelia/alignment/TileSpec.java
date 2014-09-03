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

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Specifies a set of mipmap level images and masks along with
 * a list of transformations to perform against them.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class TileSpec {

    private TreeMap<Integer, ImageAndMask> mipmapLevels;
    private int width;
    private int height;
    private double minIntensity;
    private double maxIntensity;
    private List<Transform> transforms;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<Integer, ImageAndMask>();
        this.width = -1;
        this.height = -1;
        this.minIntensity = 0;
        this.maxIntensity = 255;
        this.transforms = new ArrayList<Transform>();
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        if (width != null) {
            this.width = width.intValue();
        }
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        if (height != null) {
            this.height = height.intValue();
        }
    }

    public void setMinIntensity(Double minIntensity) {
        if (minIntensity != null) {
            this.minIntensity = minIntensity;
        }
    }

    public double getMinIntensity() {
        return minIntensity;
    }

    public double getMaxIntensity() {
        return maxIntensity;
    }

    public void setMaxIntensity(Double maxIntensity) {
        if (maxIntensity != null) {
            this.maxIntensity = maxIntensity;
        }
    }

    public void putMipmap(Integer level,
                          ImageAndMask value) {
        this.mipmapLevels.put(level, value);
    }

    public void addTransforms(List<Transform> transforms) {
        this.transforms.addAll(transforms);
    }

    public Map.Entry<Integer, ImageAndMask> getFirstMipMapEntry() {
        return mipmapLevels.firstEntry();
    }

    public Map.Entry<Integer, ImageAndMask> getFloorMipMapEntry(Integer mipMapLevel) {
        Map.Entry<Integer, ImageAndMask> floorEntry = mipmapLevels.floorEntry(mipMapLevel);
        if (floorEntry == null) {
            floorEntry = mipmapLevels.firstEntry();
        }
        return floorEntry;
    }

    /**
     * @throws IllegalArgumentException
     *   if this specification is invalid.
     */
    public void validate() throws IllegalArgumentException {

        if (mipmapLevels.size() == 0) {
            throw new IllegalArgumentException("tile specification does not contain any mipmapLevel elements");
        }

        for (ImageAndMask imageAndMask : mipmapLevels.values()) {
            imageAndMask.validate();
        }

        for (Transform transform : transforms) {
            transform.validate();
        }
    }

    public CoordinateTransformList<CoordinateTransform> createTransformList()
            throws IllegalArgumentException {

        final CoordinateTransformList<CoordinateTransform> ctl = new CoordinateTransformList< CoordinateTransform >();
        if (transforms != null) {
            for (Transform t : transforms) {
                ctl.add(t.createTransform());
            }
        }

        return ctl;
    }

}
