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
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.transform.TransformMesh;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;

/**
 * Specifies a set of mipmap level images and masks along with
 * a list of transformations to perform against them.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class TileSpec implements Serializable {

    private String tileId;
    private LayoutData layout;
    private String groupId;
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
    private MipmapPathBuilder mipmapPathBuilder;
    private ListTransformSpec transforms;
    private double meshCellSize = RenderParameters.DEFAULT_MESH_CELL_SIZE;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<>();
        this.transforms = new ListTransformSpec();
    }

    public String getTileId() {
        return tileId;
    }

    public void setTileId(final String tileId) {
        this.tileId = tileId;
    }

    public LayoutData getLayout() {
        return layout;
    }

    public void setLayout(final LayoutData layout) {
        this.layout = layout;
    }

    public String getGroupId() {
        return groupId;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setGroupId(final String groupId) {
        this.groupId = groupId;
    }

    public Double getZ() {
        return z;
    }

    public void setZ(final Double z) {
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

    public double getMeshCellSize() {
        return meshCellSize;
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
     * @param  box  coordinates that define the bounding box for this tile.
     */
    public void setBoundingBox(final Rectangle box, final double meshCellSize) {
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
                                 width,
                                 height);
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
                                           width,
                                           height);
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
    public void deriveBoundingBox(final double meshCellSize, final boolean force)
            throws IllegalStateException {
        if (force || (!isBoundingBoxDefined(meshCellSize))) {
            final TransformMesh mesh = getTransformMesh(meshCellSize);
            setBoundingBox(mesh.getBoundingBox(), meshCellSize);
        }
    }


    /**
     * @param  tileSpecs     collection of tile specs.
     * @param  meshCellSize  specifies the resolution to estimate the individual bounding boxes.
     * @param  force         if true, attributes will always be derived;
     *                       otherwise attributes will only be derived if they do not already exist.
     * @param  preallocated  optional pre-allocated bounding box instance to use for result.
     *
     * @return the bounding box of a collection of {@link TileSpec}s.
     *         The returned bounding box is the union rectangle of all tiles individual bounding boxes.
     */
    public static Rectangle2D.Double deriveBoundingBox(
            final Iterable<TileSpec> tileSpecs,
            final double meshCellSize,
            final boolean force,
            final Rectangle2D.Double preallocated) throws IllegalStateException {
        final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE };
        final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (final TileSpec t : tileSpecs) {
            t.deriveBoundingBox(meshCellSize, force);
            final double tMinX = t.getMinX();
            final double tMinY = t.getMinY();
            final double tMaxX = t.getMaxX();
            final double tMaxY = t.getMaxY();
            if (min[0] > tMinX)
                min[0] = tMinX;
            if (min[1] > tMinY)
                min[1] = tMinY;
            if (max[0] < tMaxX)
                max[0] = tMaxX;
            if (max[1] < tMaxY)
                max[1] = tMaxY;
        }

        final Rectangle2D.Double box;
        if (preallocated == null)
            box = new Rectangle2D.Double(
                    min[0],
                    min[1],
                    max[0] - min[0],
                    max[1] - min[1]);
        else {
            box = preallocated;
            box.setRect(min[0], min[1], max[0] - min[0], max[1] - max[1]);
        }
        return box;
    }

    /**
     * @param  x  local x coordinate to transform into world coordinate.
     * @param  y  local y coordinate to transform into world coordinate.
     *
     * @return world coordinates (x, y, z) for the specified local coordinates.
     */
    public double[] getWorldCoordinates(final double x, final double y) {
        final double[] worldCoordinates;
        final double[] w = new double[] {x, y};

        if (hasTransforms()) {
            final CoordinateTransformList<CoordinateTransform> ctl = getTransformList();
            ctl.applyInPlace(w);
        }

        if (z == null) {
            worldCoordinates = w;
        } else {
            worldCoordinates = new double[]{w[0], w[1], z};
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
    public double[] getLocalCoordinates(final double x, final double y, final double meshCellSize)
            throws IllegalStateException, NoninvertibleModelException {

        final double[] localCoordinates;
        final double[] l = new double[] {x, y};
        if (hasTransforms()) {
            final CoordinateTransformMesh mesh = getCoordinateTransformMesh(meshCellSize);
            mesh.applyInverseInPlace(l);
        }

        if (z == null) {
            localCoordinates = l;
        } else {
            localCoordinates = new double[]{l[0], l[1], z};
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

    public void setWidth(final Double width) {
        this.width = width;
    }

    public int getHeight() {
        int value = -1;
        if (height != null) {
            value = height.intValue();
        }
        return value;
    }

    public void setHeight(final Double height) {
        this.height = height;
    }

    public void setMinIntensity(final Double minIntensity) {
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

    public void setMaxIntensity(final Double maxIntensity) {
        this.maxIntensity = maxIntensity;
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return true if this tile spec contains mipmap for the specified level; otherwise false.
     */
    public boolean hasMipmap(final Integer level) {
        return mipmapLevels.containsKey(level);
    }

    /**
     * @param  level  desired mipmap level.
     *
     * @return the mipmap for the specified level or null if none exists.
     */
    public ImageAndMask getMipmap(final Integer level) {
        return mipmapLevels.get(level);
    }

    public void putMipmap(final Integer level,
                          final ImageAndMask value) {
        this.mipmapLevels.put(level, value);
    }

    public Map.Entry<Integer, ImageAndMask> getFirstMipmapEntry() {
        return mipmapLevels.firstEntry();
    }

    public Map.Entry<Integer, ImageAndMask> getFloorMipmapEntry(final Integer mipmapLevel) {

        Map.Entry<Integer, ImageAndMask> floorEntry = mipmapLevels.floorEntry(mipmapLevel);

        if (floorEntry == null) {
            floorEntry = mipmapLevels.firstEntry();
        } else if ((floorEntry.getKey() < mipmapLevel) && (mipmapPathBuilder != null)) {
            floorEntry = mipmapPathBuilder.deriveImageAndMask(mipmapLevel,
                                                              mipmapLevels.firstEntry(),
                                                              true);
        }

        return floorEntry;
    }

    public void setMipmapPathBuilder(final MipmapPathBuilder mipmapPathBuilder) {
        this.mipmapPathBuilder = mipmapPathBuilder;
    }

    public boolean hasTransforms() {
        return ((transforms != null) && (transforms.size() > 0));
    }

    public ListTransformSpec getTransforms() {
        return transforms;
    }

    public void setTransforms(final ListTransformSpec transforms) {
        this.transforms = transforms;
    }

    public void addTransformSpecs(final List<TransformSpec> transformSpecs) {
        transforms.addAllSpecs(transformSpecs);
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

        for (final ImageAndMask imageAndMask : mipmapLevels.values()) {
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
     * Get a copy of this {@link TileSpec}'s transforms as a {@link CoordinateTransformList}.
     * If this {@link TileSpec} does not have any transforms, an empty list is returned.
     *
     * The returned list is no longer cached, so it can be used/changed safely without affecting this {@link TileSpec}.
     *
     * @return transform list copy for this tile spec.
     *
     * @throws IllegalArgumentException
     *   if the list cannot be generated.
     */
    public CoordinateTransformList<CoordinateTransform> getTransformList()
            throws IllegalArgumentException {

        final CoordinateTransformList<CoordinateTransform> ctl;
        if (transforms == null) {
            ctl = new CoordinateTransformList<>();
        } else {
            ctl = transforms.getNewInstanceAsList();
        }

        return ctl;
    }

    public String toLayoutFileFormat() {

        String affineData = null;
        if (hasTransforms()) {
            final TransformSpec lastSpec = transforms.getSpec(transforms.size() - 1);
            if (lastSpec instanceof LeafTransformSpec) {
                final LeafTransformSpec leafSpec = (LeafTransformSpec) lastSpec;
                final String[] data = WHITESPACE_PATTERN.split(leafSpec.getDataString(), -1);
                if (data.length == 6) {

                    // Need to translate affine matrix order "back" for Karsh aligner.
                    //
                    // Given: A D
                    //        B E
                    //        C F
                    //
                    // Saalfeld order is:      A D B E C F
                    // Karsh aligner order is: A B C D E F

                    affineData = data[0] + '\t' + data[2] + '\t' + data[4] + '\t' +
                                 data[1] + '\t' + data[3] + '\t' + data[5];

                } else if (data.length > 6) {

                    // hack to export polynomial data in layout format (keep in Saalfeld order)

                    final int lengthMinusOne = data.length - 1;
                    final StringBuilder sb = new StringBuilder(1024);

                    sb.append(data.length);
                    sb.append('\t');

                    for (int i = 0; i < lengthMinusOne; i++) {
                        sb.append(data[i]);
                        sb.append('\t');
                    }
                    sb.append(data[lengthMinusOne]);

                    affineData = sb.toString();
                }

            }
        }

        String sectionId = null;
        Integer imageCol = null;
        Integer imageRow = null;
        String camera = null;
        String temca = null;
        Double rotation = null;
        if (layout != null) {
            sectionId = layout.getSectionId();
            imageCol = layout.getImageCol();
            imageRow = layout.getImageRow();
            camera = layout.getCamera();
            temca = layout.getTemca();
            rotation = layout.getRotation();
            if (affineData == null) {
                affineData = "1.0\t0.0\t" + layout.getStageX() + "\t0.0\t1.0\t" + layout.getStageY();
            }
        }

        String rawPath = null;
        final Map.Entry<Integer, ImageAndMask> firstMipmapEntry = getFirstMipmapEntry();
        if (firstMipmapEntry != null) {
            final ImageAndMask imageAndMask = firstMipmapEntry.getValue();
            rawPath = imageAndMask.getImageFilePath();
        }

        // sectionId, 1.0, 0.0, stageX, 0.0, 1.0, stageY, imageCol, imageRow, camera, rawPath, temca, rotation, z
        return sectionId + '\t' + tileId + '\t' + affineData + '\t' +
               imageCol + '\t' + imageRow + '\t' + camera + '\t' + rawPath + '\t' + temca + '\t' + rotation + '\t' + z;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static TileSpec fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<TileSpec> fromJsonArray(final String json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileSpec[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<TileSpec> fromJsonArray(final Reader json)
            throws IOException {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileSpec[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final JsonUtils.Helper<TileSpec> JSON_HELPER =
            new JsonUtils.Helper<>(TileSpec.class);
}
