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
    private Double width;
    private Double height;
    private Double minIntensity;
    private Double maxIntensity;
    private List<Transform> transforms;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<Integer, ImageAndMask>();
        this.transforms = new ArrayList<Transform>();
    }

    public int getWidth() {
        int value = -1;
        if (width != null) {
            value = width.intValue();
        }
        return value;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public int getHeight() {
        int value = -1;
        if (height != null) {
            value = height.intValue();
        }
        return value;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public void setMinIntensity(Double minIntensity) {
        this.minIntensity = minIntensity;
    }

    public double getMinIntensity() {
        double value = 0;
        if (minIntensity != null) {
            value = minIntensity;
        }
        return value;
    }

    public double getMaxIntensity() {
        double value = 255;
        if (maxIntensity != null) {
            value = maxIntensity;
        }
        return value;
    }

    public void setMaxIntensity(Double maxIntensity) {
        this.maxIntensity = maxIntensity;
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return true if this tile spec contains mipmap for the specified level; otherwise false.
     */
    public boolean hasMipmap(Integer level) {
        return mipmapLevels.containsKey(level);
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return the mipmap for the specified level or null if none exists.
     */
    public ImageAndMask getMipmap(Integer level) {
        return mipmapLevels.get(level);
    }

    public void putMipmap(Integer level,
                          ImageAndMask value) {
        this.mipmapLevels.put(level, value);
    }

    public void addTransforms(List<Transform> transforms) {
        this.transforms.addAll(transforms);
    }

    public Map.Entry<Integer, ImageAndMask> getFirstMipmapEntry() {
        return mipmapLevels.firstEntry();
    }

    public Map.Entry<Integer, ImageAndMask> getFloorMipmapEntry(Integer mipmapLevel) {
        Map.Entry<Integer, ImageAndMask> floorEntry = mipmapLevels.floorEntry(mipmapLevel);
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
