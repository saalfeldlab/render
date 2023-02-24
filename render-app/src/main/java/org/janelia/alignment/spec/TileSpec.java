/*
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

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.trakem2.transform.TransformMesh;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader;
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
    @SuppressWarnings("unused")   // older JSON specs without channels might have minIntensity explicitly specified
    private Double minIntensity;
    @SuppressWarnings("unused")   // older JSON specs without channels might have maxIntensity explicitly specified
    private Double maxIntensity;
    private TreeMap<Integer, ImageAndMask> mipmapLevels;
    private List<ChannelSpec> channels;
    private MipmapPathBuilder mipmapPathBuilder;
    @SuppressWarnings("unused")
    private FilterSpec filterSpec;
    private ListTransformSpec transforms;
    private double meshCellSize = RenderParameters.DEFAULT_MESH_CELL_SIZE;

    private Set<String> labels;

    /** cached mesh to speed up local coordinates calculations (see {@link #getLocalCoordinates}) */
    @JsonIgnore
    private transient CoordinateTransformMesh localCoordinatesMesh = null;

    public TileSpec() {
        this.mipmapLevels = new TreeMap<>();
        this.transforms = new ListTransformSpec();
        this.labels = null;
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

    @JsonIgnore
    public String getSectionId() {
        return layout == null ? null : layout.getSectionId();
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

    public boolean hasLabel(final String label) {
        return (labels != null) && labels.contains(label);
    }

    public void addLabel(final String label) {
        if (labels == null) {
            labels = new HashSet<>();
        }
        labels.add(label);
    }

    public boolean isBoundingBoxDerivationNeeded(final double meshCellSize) {
        return (this.meshCellSize != meshCellSize) ||
               (minX == null) || (minY == null) || (maxX == null) || (maxY == null);
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

    public TileBounds toTileBounds() {
        return new TileBounds(tileId, getSectionId(), z, minX, minY, maxX, maxY);
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

        if (isMissingWidthOrHeight()) {
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

        if (isMissingWidthOrHeight()) {
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
    public void deriveBoundingBox(final double meshCellSize, final boolean force, final boolean sloppy)
            throws IllegalStateException {

        if (force || isBoundingBoxDerivationNeeded(meshCellSize)) {
            if (sloppy) {
                if (isMissingWidthOrHeight()) {
                    throw new IllegalStateException("width and height must be set to create a bounding box");
                }

                final CoordinateTransformList<CoordinateTransform> ctList = getTransformList();
                final ArrayList<double[]> borderSamples = new ArrayList<>();

                /* top and bottom */
                final int numXs = (int)Math.max(2, Math.round(width / meshCellSize));
                final double dx = (width - 1) / (numXs - 1);

                for (double xi =0; xi < numXs; ++xi) {
                    final double x = xi * dx;
                    borderSamples.add(new double[]{x, 0});
                    borderSamples.add(new double[]{x, height});
                }

                /* left and right */
                final int numYs = (int)Math.max(2, Math.round(height / meshCellSize));
                final double dy = (height - 1) / (numYs - 1);

                for (double yi =0; yi < numYs; ++yi) {
                    final double y = yi * dy;
                    borderSamples.add(new double[]{0, y});
                    borderSamples.add(new double[]{width, y});
                }
                double xMin = Double.MAX_VALUE;
                double yMin = Double.MAX_VALUE;

                double xMax = -Double.MAX_VALUE;
                double yMax = -Double.MAX_VALUE;

                for (final double[] point : borderSamples) {
                    ctList.applyInPlace(point);

                    if ( point[ 0 ] < xMin ) xMin = point[ 0 ];
                    if ( point[ 0 ] > xMax ) xMax = point[ 0 ];
                    if ( point[ 1 ] < yMin ) yMin = point[ 1 ];
                    if ( point[ 1 ] > yMax ) yMax = point[ 1 ];
                }

                setBoundingBox(new Rectangle((int)xMin, (int)yMin, (int)Math.ceil(xMax - xMin), (int)Math.ceil(yMax - yMin)), meshCellSize);
//                setBoundingBox(new Rectangle((int)xMin, (int)yMin, (int)(xMax - xMin), (int)(yMax - yMin)), meshCellSize);

            } else {
                final TransformMesh mesh = getTransformMesh(meshCellSize);
                setBoundingBox(mesh.getBoundingBox(), meshCellSize);
            }
        }
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

        deriveBoundingBox(meshCellSize, force, true);
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
            box.setRect(min[0], min[1], max[0] - min[0], max[1] - min[1]);
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
            if (localCoordinatesMesh == null) {
                localCoordinatesMesh = getCoordinateTransformMesh(meshCellSize);
            } // else assume cached localCoordinatesMesh for this tile spec has same meshCellSize
            localCoordinatesMesh.applyInverseInPlace(l);
        }

        if (z == null) {
            localCoordinates = l;
        } else {
            localCoordinates = new double[]{l[0], l[1], z};
        }

        return localCoordinates;
    }

    public boolean isWorldCoordinateInsideTile(final double worldX,
                                               final double worldY)
            throws IllegalStateException, NoninvertibleModelException {

        boolean isInside = false;

        if ((worldX >= minX) && (worldX <= maxX) &&
            (worldY >= minY) && (worldY <= maxY)) {
            final double[] local = getLocalCoordinates(worldX, worldY, meshCellSize);
            isInside = ((local[0] >= 0.0) && (local[0] <= width) &&
                        (local[1] >= 0.0) && (local[1] <= height));
        }

        return isInside;
    }

    public boolean isMissingWidthOrHeight() {
        return ((width == null) || (height == null));
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

    @JsonIgnore
    public double[][] getRawCornerLocations() {
        return new double[][] {
                {               0,                0 },
                { this.getWidth(),                0 },
                {               0, this.getHeight() },
                { this.getWidth(), this.getHeight() }
        };
    }

    /**
     * @return raw corner locations with match process (e.g. lens correction) transformations applied
     *         as a list of {@link Point} objects.
     */
    @JsonIgnore
    public List<Point> getMatchingTransformedCornerPoints() {
        return getMatchingTransformedPoints(getRawCornerLocations());
    }

    @JsonIgnore
    public List<Point> getMatchingTransformedPoints(final double[][] rawLocations) {
        final List<Point> transformedCornerPoints = new ArrayList<>();
        final CoordinateTransformList<CoordinateTransform> transformList = this.getMatchingTransformList();
        for (final double[] rawLocation : rawLocations)  {
            transformedCornerPoints.add(new Point(transformList.apply(rawLocation)));
        }
        return transformedCornerPoints;
    }

    @JsonIgnore
    public List<ChannelSpec> getAllChannels() {
        final List<ChannelSpec> channelList;
        if ((channels == null) || (channels.size() == 0)) {
            channelList = Collections.singletonList(new ChannelSpec(null,
                                                                    minIntensity,
                                                                    maxIntensity,
                                                                    mipmapLevels,
                                                                    mipmapPathBuilder,
                                                                    filterSpec));
        } else {
            channelList = channels;
        }
        return channelList;
    }

    public List<ChannelSpec> getChannels(final Set<String> withNames) {
        final List<ChannelSpec> channelList = new ArrayList<>();
        if ((channels == null) || (channels.size() == 0)) {
            if (withNames.contains(null)) {
                channelList.add(new ChannelSpec(null,
                                                minIntensity,
                                                maxIntensity,
                                                mipmapLevels,
                                                mipmapPathBuilder,
                                                filterSpec));
            }
        } else {
            for (final ChannelSpec channelSpec : channels) {
                if (withNames.contains(channelSpec.getName())) {
                    channelList.add(channelSpec);
                }
            }
        }
        return channelList;
    }

    public String getFirstChannelName() {
        String firstChannelName = null;
        final List<ChannelSpec> channelSpecs = getAllChannels();
        if (channelSpecs.size() > 0) {
            firstChannelName = channelSpecs.get(0).getName();
        }
        return firstChannelName;
    }

    /**
     * Converts legacy single channel tile spec mipmap data to a channel spec with the specified name.
     *
     * @param  channelName  name of the single channel.
     *
     * @throws IllegalStateException
     *   if this tile spec already has defined channels.
     */
    public void convertLegacyToChannel(final String channelName)
            throws IllegalStateException {
        if (channels == null) {
            channels = new ArrayList<>();
            channels.add(new ChannelSpec(channelName,
                                         minIntensity,
                                         maxIntensity,
                                         mipmapLevels,
                                         mipmapPathBuilder,
                                         filterSpec));
            this.minIntensity = null;
            this.maxIntensity = null;
            this.mipmapLevels = null;
            this.mipmapPathBuilder = null;
        } else {
            throw new IllegalStateException("channels already defined");
        }
    }

    public void convertSingleChannelSpecToLegacyForm()
            throws IllegalStateException {
        if (channels != null) {
            if (channels.size() != 1) {
                throw new IllegalStateException(channels.size() +
                                                " channels exist but must only have one to convert to legacy form");
            }
            final ChannelSpec channelSpec = channels.get(0);
            channels = null;
            minIntensity = channelSpec.getMinIntensity();
            maxIntensity = channelSpec.getMaxIntensity();
            mipmapLevels = channelSpec.getMipmapLevels();
            mipmapPathBuilder = channelSpec.getMipmapPathBuilder();
            filterSpec = channelSpec.getFilterSpec();
        }
    }

    public void addChannel(final ChannelSpec channelSpec) {
        if (channels == null) {
            channels = new ArrayList<>();
        }
        channels.add(channelSpec);
    }

    @JsonIgnore
    public Map.Entry<Integer, ImageAndMask> getFirstMipmapEntry() {
        final Map.Entry<Integer, ImageAndMask> firstEntry;
        if ((channels == null) || (channels.size() == 0)) {
            firstEntry = mipmapLevels.firstEntry();
        } else {
            firstEntry = channels.get(0).getFirstMipmapEntry();
        }
        return firstEntry;
    }

    public void setMinAndMaxIntensity(final double minIntensity,
                                      final double maxIntensity,
                                      final String forChannelName) {

        if (channels == null) {

            this.minIntensity = minIntensity;
            this.maxIntensity = maxIntensity;

        } else {

            if (forChannelName == null) {

                for (final ChannelSpec channelSpec : channels) {
                    if (channelSpec.getName() == null) {
                        channelSpec.setMinAndMaxIntensity(minIntensity, maxIntensity);
                        break;
                    }
                }

            } else {

                for (final ChannelSpec channelSpec : channels) {
                    if (forChannelName.equals(channelSpec.getName())) {
                        channelSpec.setMinAndMaxIntensity(minIntensity, maxIntensity);
                        break;
                    }
                }

            }

        }

    }

    public void setMipmapPathBuilder(final MipmapPathBuilder mipmapPathBuilder) {
        this.mipmapPathBuilder = mipmapPathBuilder;
        if (channels != null) {
            for (final ChannelSpec channelSpec : channels) {
                channelSpec.setMipmapPathBuilder(mipmapPathBuilder);
            }
        }
    }

    public FilterSpec getFilterSpec() {
        FilterSpec spec = filterSpec;
        if ((spec == null) && (channels != null) && (channels.size() > 0)) {
            spec = channels.get(0).getFilterSpec();
        }
        return spec;
    }

    public void setFilterSpec(final FilterSpec filterSpec) {
        this.filterSpec = filterSpec;
        if (channels != null) {
            for (final ChannelSpec channelSpec : channels) {
                channelSpec.setFilterSpec(filterSpec);
            }
        }
    }

    public boolean hasTransforms() {
        return ((transforms != null) && (transforms.size() > 0));
    }

    public boolean hasTransformWithLabel(final String label) {
        boolean hasLabel = false;
        if (transforms != null) {
            hasLabel = transforms.hasLabel(label);
        }
        return hasLabel;
    }

    /**
     * @return true if this tile spec has at least one channel with a mask.
     */
    public boolean hasMasks() {
        boolean hasMasks = false;
        for (final ChannelSpec channelSpec : getAllChannels()) {
            if (channelSpec.hasMask()) {
                hasMasks = true;
                break;
            }
        }
        return hasMasks;
    }

    /**
     * Utility for altering this tile spec to support rendering/exporting of transformed mask data.
     * If the first channel has a mask, replace the source image with that mask.
     * Otherwise, replace the source image with a dynamic empty mask that covers the full tile area.
     * Also removes this tile spec's filter if it has one.
     */
    public void replaceFirstChannelImageWithItsMask() {

        final TreeMap<Integer, ImageAndMask> levels;
        if ((channels == null) || (channels.size() == 0)) {
            levels = mipmapLevels;
            filterSpec = null;
        } else {
            final ChannelSpec firstChannelSpec = channels.get(0);
            levels = firstChannelSpec.getMipmapLevels();
            firstChannelSpec.setFilterSpec(null);
        }

        final Map.Entry<Integer, ImageAndMask> firstEntry = levels.firstEntry();
        final ImageAndMask imageAndMask = firstEntry.getValue();
        if (imageAndMask.hasMask()) {
            levels.put(firstEntry.getKey(),
                       new ImageAndMask(imageAndMask.getMaskUrl(),
                                        imageAndMask.getMaskLoaderType(),
                                        imageAndMask.getMaskSliceNumber(),
                                        null,
                                        null,
                                        null));
        } else {
            final String emptyMaskUrl = DynamicMaskLoader.buildEmptyMaskDescription(getWidth(),
                                                                                    getHeight()).toString();
            levels.put(firstEntry.getKey(),
                       new ImageAndMask(emptyMaskUrl,
                                        ImageLoader.LoaderType.DYNAMIC_MASK,
                                        null,
                                        null,
                                        null,
                                        null));
        }
    }

    public ListTransformSpec getTransforms() {
        return transforms;
    }

    @JsonIgnore
    public TransformSpec getLastTransform() {
        TransformSpec lastTransform = null;
        if (hasTransforms()) {
            lastTransform = transforms.getLastSpec();
        }
        return lastTransform;
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

    /**
     * Replace this tile's possibly nested transform list with a flattened version.
     */
    public void flattenTransforms() {
        final ListTransformSpec flattenedList = new ListTransformSpec();
        transforms.flatten(flattenedList);
        transforms = flattenedList;
    }

    /**
     * Replace this tile's nested transform list with a flattened version
     * and optionally filter/exclude labelled transforms.
     *
     * @param  excludeAll                     if true, removes all transforms.
     *                                        Specify as null to skip.
     *
     * @param  excludeAfterLastLabels         removes all transforms after the last occurrence
     *                                        of a transform with one of these labels.
     *                                        Specify as null to skip.
     *
     * @param  excludeFirstAndAllAfterLabels  removes the first transform with one of these labels.
     *                                        Specify as null to skip.
     */
    public void flattenAndFilterTransforms(final Boolean excludeAll,
                                           final Set<String> excludeAfterLastLabels,
                                           final Set<String> excludeFirstAndAllAfterLabels) {
        if ((excludeAll != null) && excludeAll) {
            transforms = new ListTransformSpec();
        } else {
            transforms = transforms.flattenAndFilter(excludeAfterLastLabels, excludeFirstAndAllAfterLabels);
        }
    }

    /**
     * @throws IllegalArgumentException
     *   if this spec's mipmaps are invalid.
     */
    public void validateMipmaps() throws IllegalArgumentException {
        for (final ChannelSpec channelSpec : getAllChannels()) {
            channelSpec.validateMipmaps(tileId);
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
     * <br/>
     * The returned list is no longer cached, so it can be used/changed safely without affecting this {@link TileSpec}.
     *
     * @return transform list copy for this tile spec.
     *
     * @throws IllegalArgumentException
     *   if the list cannot be generated.
     */
    @JsonIgnore
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

    /**
     * Get a copy of this spec's match (e.g. lens correction) transforms as a {@link CoordinateTransformList}.
     * If this spec does not have any transforms, an empty list is returned.
     *
     * @return list of transforms used for point match derivation.
     *
     * @throws IllegalArgumentException
     *   if the list cannot be generated.
     */
    @JsonIgnore
    public CoordinateTransformList<CoordinateTransform> getMatchingTransformList()
            throws IllegalArgumentException {

        final CoordinateTransformList<CoordinateTransform> ctl;
        if (transforms == null) {
            ctl = new CoordinateTransformList<>();
        } else {
            final ListTransformSpec matchTransforms = transforms.getMatchSpecList();
            ctl = matchTransforms.getNewInstanceAsList();
        }

        return ctl;
    }

    /**
     * Get a copy of this spec's post match transforms as a {@link CoordinateTransformList}.
     * If this spec does not have any transforms, an empty list is returned.
     *
     * @return list of transforms not used for point match derivation.
     *
     * @throws IllegalArgumentException
     *   if the list cannot be generated.
     */
    @JsonIgnore
    public CoordinateTransformList<CoordinateTransform> getPostMatchingTransformList()
            throws IllegalArgumentException {

        final CoordinateTransformList<CoordinateTransform> ctl;
        if (transforms == null) {
            ctl = new CoordinateTransformList<>();
        } else {
            final ListTransformSpec postMatchTransforms = transforms.getPostMatchSpecList();
            ctl = postMatchTransforms.getNewInstanceAsList();
        }

        return ctl;
    }

    @Override
    public String toString() {
        return tileId;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public TileSpec slowClone() {
        final String json = this.toJson();
        return TileSpec.fromJson(json);
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
            throws IllegalArgumentException {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileSpec[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final JsonUtils.Helper<TileSpec> JSON_HELPER =
            new JsonUtils.Helper<>(TileSpec.class);
}
