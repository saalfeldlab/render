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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

/**
 * Specifies a set of mipmap level images and masks along with
 * a list of transformations to perform against them.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class TileSpec {

    private TreeMap<String, ImageAndMask> mipmapLevels;
    private int width;
    private int height;
    private double minIntensity;
    private double maxIntensity;
    private List<Transform> transforms;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<String, ImageAndMask>(LEVEL_COMPARATOR);
        this.width = -1;
        this.height = -1;
        this.minIntensity = 0;
        this.maxIntensity = 255;
        this.transforms = new ArrayList<Transform>();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getMinIntensity() {
        return minIntensity;
    }

    public double getMaxIntensity() {
        return maxIntensity;
    }

    public Map.Entry<String, ImageAndMask> getFirstMipMapEntry() {
        return mipmapLevels.firstEntry();
    }

    public Map.Entry<String, ImageAndMask> getFloorMipMapEntry(String mipMapLevel) {
        Map.Entry<String, ImageAndMask> floorEntry = mipmapLevels.floorEntry(mipMapLevel);
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

    public void addLevel(String level,
                         ImageAndMask imageAndMask) {
        mipmapLevels.put(level, imageAndMask);
    }

    public void addTransform(Transform transform) {
        transforms.add(transform);
    }

    private static final Comparator<String> LEVEL_COMPARATOR = new Comparator<String>() {
        @Override
        public int compare(String o1,
                           String o2) {
            final Integer i1 = new Integer(o1);
            final Integer i2 = new Integer(o2);
            return  i1.compareTo(i2);
        }
    };
}
