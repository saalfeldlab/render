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

import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.transform.TransformMesh;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;

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
    private final TreeMap<Integer, ImageAndMask> mipmapLevels;
    private ListTransformSpec transforms;
    private double meshCellSize = RenderParameters.DEFAULT_MESH_CELL_SIZE; 
    
    public TileSpec() {
        this.mipmapLevels = new TreeMap<Integer, ImageAndMask>();
        this.transforms = new ListTransformSpec();
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

    public Double getMinX() {
        return minX;
    }

    public Double getMinY() {
        return minY;
    }
    
    public Double getMaxX() {
        return maxX;
    }

    public Double getMaxY() {
        return maxY;
    }
    
    public boolean isBoundingBoxDefined(final double meshCellSize) {
        return 
                (this.meshCellSize == meshCellSize) &&
                (minX != null) &&
                (minY != null) &&
                (maxX != null) &&
                (maxY != null);
    }
    
    /**
     * The bounding box is only valid for a given meshCellSize, i.e. setting it
     * independently of the meshCellSize is potentially harmful.
     * 
     * @param box
     */
    public void setBoundingBox(Rectangle box, final double meshCellSize) {
        this.minX = box.getX();
        this.minY = box.getY();
        this.maxX = box.getMaxX();
        this.maxY = box.getMaxY();
        this.meshCellSize = meshCellSize;
    }

    public int getNumberOfTrianglesCoveringWidth(final double meshCellSize) {
        return (int) (width / meshCellSize + 0.5);
    }

    /**
     * @return a transform mesh built from this spec's list of transforms.
     *
     * @throws IllegalStateException
     *   if width or height have not been defined for this tile.
     */
    public TransformMesh getTransformMesh(final double meshCellSize)
            throws IllegalStateException {

        if (! hasWidthAndHeightDefined()) {
            throw new IllegalStateException("width and height must be set to create transform mesh");
        }

        final CoordinateTransformList<CoordinateTransform> ctList = getTransformList();
        return new TransformMesh(ctList,
                                 getNumberOfTrianglesCoveringWidth(meshCellSize),
                                 width.floatValue(),
                                 height.floatValue());
    }

    /**
     * @return a coordinate transform mesh built from this spec's list of transforms.
     *
     * @throws IllegalStateException
     *   if width or height have not been defined for this tile.
     */
    public CoordinateTransformMesh getCoordinateTransformMesh(final double meshCellSize)
            throws IllegalStateException {

        if (! hasWidthAndHeightDefined()) {
            throw new IllegalStateException("width and height must be set to create transform mesh");
        }

        final CoordinateTransformList<CoordinateTransform> ctList = getTransformList();
        return new CoordinateTransformMesh(ctList,
                                           getNumberOfTrianglesCoveringWidth(meshCellSize),
                                           width.floatValue(),
                                           height.floatValue());
    }

    /**
     * Derives this tile's bounding box attributes.
     *
     * @param  force  if true, attributes will always be derived;
     *                otherwise attributes will only be derived if they do not already exist.
     *
     * @throws IllegalStateException
     *   if width or height have not been defined for this tile.
     */
    public void deriveBoundingBox(final double meshCellSize, boolean force)
            throws IllegalStateException {
        if (force || (!isBoundingBoxDefined(meshCellSize))) {
            final TransformMesh mesh = getTransformMesh(meshCellSize);
            setBoundingBox(mesh.getBoundingBox(), meshCellSize);
        }
    }

    /**
     * @param  x  local x coordinate to transform into world coordinate.
     * @param  y  local y coordinate to transform into world coordinate.
     *
     * @return world coordinates (x, y, z) for the specified local coordinates.
     */
    public float[] getWorldCoordinates(float x,
                                       float y) {
        float[] worldCoordinates;
        float[] w = new float[] {x, y};

        if (hasTransforms()) {
            final CoordinateTransformList<CoordinateTransform> ctl = getTransformList();
            ctl.applyInPlace(w);
        }

        if (z == null) {
            worldCoordinates = w;
        } else {
            worldCoordinates = new float[]{w[0], w[1], z.floatValue()};
        }

        return worldCoordinates;
    }

    /**
     * @param  x  world x coordinate to inversely transform into local coordinate.
     * @param  y  world y coordinate to inversely transform into local coordinate.
     *
     * @return local coordinates (x, y, z) for the specified world coordinates.
     *
     * @throws IllegalStateException
     *   if width or height have not been defined for this tile.
     *
     * @throws NoninvertibleModelException
     *   if this tile's transforms cannot be inverted for the specified point.
     */
    public float[] getLocalCoordinates(float x, float y, final double meshCellSize)
            throws IllegalStateException, NoninvertibleModelException {

        float[] localCoordinates;
        float[] l = new float[] {x, y};
        if (hasTransforms()) {
            final CoordinateTransformMesh mesh = getCoordinateTransformMesh(meshCellSize);
            mesh.applyInverseInPlace(l);
        }

        if (z == null) {
            localCoordinates = l;
        } else {
            localCoordinates = new float[]{l[0], l[1], z.floatValue()};
        }

        return localCoordinates;
    }

    public boolean hasWidthAndHeightDefined() {
        return ((width != null) && (height != null));
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

    public int numberOfTransforms() {
        return (transforms == null) ? 0 : transforms.size();
    }

    public ListTransformSpec getTransforms() {
        return transforms;
    }

    public void addTransformSpecs(List<TransformSpec> transformSpecs) {
        transforms.addAllSpecs(transformSpecs);
    }

    public void setTransformSpec(int index,
                                 TransformSpec transformSpec)
            throws IllegalArgumentException {

        if (index == transforms.size()) {
            transforms.addSpec(transformSpec);
        } else if ((index < transforms.size()) && (index >= 0)) {
            transforms.setSpec(index, transformSpec);
        } else {
            throw new IllegalArgumentException("transform index (" + index +
                                               ") must be between 0 and " +
                                               transforms.size() + " for tile spec " + tileId);
        }
    }

    public void removeLastTransformSpec() {
        transforms.removeLastSpec();
    }

    public void flattenTransforms() {
        final ListTransformSpec flattenedList = new ListTransformSpec();
        transforms.flatten(flattenedList);
        transforms = flattenedList;
    }

    /**
     * @throws IllegalArgumentException
     *   if this spec's mipmaps are invalid.
     */
    public void validateMipmaps() throws IllegalArgumentException {
        if (mipmapLevels.size() == 0) {
            throw new IllegalArgumentException("tile specification with id '" + tileId +
                                               "' does not contain any mipmapLevel elements");
        }

        for (ImageAndMask imageAndMask : mipmapLevels.values()) {
            imageAndMask.validate();
        }
    }

    /**
     * @throws IllegalArgumentException
     *   if this specification is invalid.
     */
    public void validate() throws IllegalArgumentException {
        validateMipmaps();
        transforms.validate();
    }

    /**
     * Get the transforms of the {@link TileSpec} as a
     * {@link CoordinateTransformList}.  If the transform of the
     * {@link TileSpec} is a list, this instance will be returned.  Otherwise,
     * a new instance containing a single simple transform, or no transform at
     * all will be returned.
     * 
     * Note that modifying the returned list can change the transforms of the
     * {@link TileSpec}, i.e. copy the list or add it to a new list if you want
     * to add other transformations.
     * 
     * TODO Think more carefully if this is a good idea at all.  Having a safe
     * to use list is probably what everybody wants from this method.  It is,
     * however, used in other contexts, e.g. to simply apply the transforms,
     * for which a more general function getTransform() would have served
     * better and simpler.
     * 
     * @return
     * @throws IllegalArgumentException
     */
    public CoordinateTransformList<CoordinateTransform> getTransformList()
            throws IllegalArgumentException {

        CoordinateTransformList<CoordinateTransform> ctl;
        if (transforms == null) {
            ctl = new CoordinateTransformList< CoordinateTransform >();
        } else {
            ctl = transforms.getInstanceAsList();
        }

        return ctl;
    }

    public String toLayoutFileFormat() {
        Integer sectionId = null;
        Integer karshTileId = null;
        Integer imageCol = null;
        Integer imageRow = null;
        String camera = null;
        String temca = null;
        Double stageX = null;
        Double stageY = null;
        Double rotation = null;
        if (layout != null) {
            sectionId = layout.getSectionId();
            karshTileId = layout.getKarshTileId();
            imageCol = layout.getImageCol();
            imageRow = layout.getImageRow();
            camera = layout.getCamera();
            temca = layout.getTemca();
            stageX = layout.getStageX();
            stageY = layout.getStageY();
            rotation = layout.getRotation();
        }

        String rawPath = null;
        final Map.Entry<Integer, ImageAndMask> firstMipmapEntry = getFirstMipmapEntry();
        if (firstMipmapEntry != null) {
            final ImageAndMask imageAndMask = firstMipmapEntry.getValue();
            rawPath = imageAndMask.getImageFilePath();
        }

        // sectionId, karshTileId, 1.0, 0.0, stageX, 0.0, 1.0, stageY, imageCol, imageRow, camera, rawPath, temca, rotation
        return String.valueOf(sectionId) + '\t' + karshTileId + '\t' +
               "1.0\t0.0\t" + stageX + "\t0.0\t1.0\t" + stageY + '\t' +
               imageCol + '\t' + imageRow + '\t' + camera + '\t' + rawPath + '\t' + temca + '\t' + rotation;
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this, TileSpec.class);
    }

    public static TileSpec fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, TileSpec.class);
    }
}
