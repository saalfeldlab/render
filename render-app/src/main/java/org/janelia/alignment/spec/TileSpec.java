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
package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMesh;
import org.janelia.alignment.ImageAndMask;

import java.awt.*;
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

    private String tileId;
    private LayoutData layout;
    private Double z;
    private Double minX;
    private Double minY;
    private Double maxX;
    private Double maxY;
    private Double width;
    private Double height;
    private Double minIntensity;
    private Double maxIntensity;
    private TreeMap<Integer, ImageAndMask> mipmapLevels;
    private List<TransformSpec> transforms;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<Integer, ImageAndMask>();
        this.transforms = new ArrayList<TransformSpec>();
    }

    public String getTileId() {
        return tileId;
    }

    public void setTileId(String tileId) {
        this.tileId = tileId;
    }

    public LayoutData getLayout() {
        return layout;
    }

    public void setLayout(LayoutData layout) {
        this.layout = layout;
    }

    public Double getZ() {
        return z;
    }

    public void setZ(Double z) {
        this.z = z;
    }

    public boolean isBoundingBoxDefined() {
        return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
    }

    public void setBoundingBox(Rectangle box) {
        this.minX = box.getX();
        this.minY = box.getY();
        this.maxX = box.getMaxX();
        this.maxY = box.getMaxY();
    }

    /**
     * Derives this tile's bounding box attributes.
     *
     * @param  force  if true, attributes will always be derived;
     *                otherwise attributes will only be derived if they do not already exist.
     *
     * @throws IllegalStateException
     *   if width, height, or transforms have not been defined for this tile.
     */
    public void deriveBoundingBox(boolean force)
            throws IllegalStateException {

        if (force || (! isBoundingBoxDefined())) {

            if ((width == null) || (height == null)) {
                throw new IllegalStateException("width and height must be set to derive bounding box");
            }

            if (! hasTransforms()) {
                throw new IllegalStateException("transforms must be set to derive bounding box");
            }

            final CoordinateTransformList<CoordinateTransform> ctList = createTransformList();
            final TransformMesh mesh = new TransformMesh(ctList,
                                                         32,
                                                         width.floatValue(),
                                                         height.floatValue());
            setBoundingBox(mesh.getBoundingBox());
        }
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

    public boolean hasTransforms() {
        return ((transforms != null) && (transforms.size() > 0));
    }

    public void addTransforms(List<TransformSpec> transforms) {
        this.transforms.addAll(transforms);
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

        for (TransformSpec transform : transforms) {
            transform.validate();
        }
    }

    public CoordinateTransformList<CoordinateTransform> createTransformList()
            throws IllegalArgumentException {

        final CoordinateTransformList<CoordinateTransform> ctl = new CoordinateTransformList< CoordinateTransform >();
        if (transforms != null) {
            for (TransformSpec spec : transforms) {
                ctl.add(spec.getInstance());
            }
        }

        return ctl;
    }

}
