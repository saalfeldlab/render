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
package org.janelia.alignment.oldcanvas;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.awt.Image;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.Serializable;

import mpicbg.models.CoordinateTransform;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public interface Canvas<T extends CoordinateTransform> extends Serializable {

    final public int DEFAULT_MESH_CELL_SIZE = 64;
    final public int DEFAULT_MIN_MESH_CELL_SIZE = 0;

    public double getWidth();
    public double getHeight();

    /**
     * Estimate the minimal bounding box required to render this {@link Canvas}
     * with a specific meshCellSize.  If <code>preallocated</code> is not null
     * it will be returned, otherwise a new
     * {@link Rectangle2D Rectangle2D.Double} will be generated.
     *
     * @param meshCellSize
     * @param preallocated
     * @return minimal bounding box
     */
    public Rectangle2D.Double getMinBounds(
            final double meshCellSize,
            final Rectangle2D.Double preallocated);

    /**
     * Get the {@link CoordinateTransform}.
     *
     * @return
     */
    public T getTransform();

    /**
     * Render the mask at a specific mipmap-level rendered with a specified
     * mesh cell size and minimum mesh cell size into an externally provided
     * {@link ByteProcessor}.  The dimensions of the {@link ByteProcessor} must
     * match or exceed those of this {@link Canvas}, tested only by assertion.
     *
     * Mask encodes alpha transparencies [0,1] into uint8 [0,255].
     */
    public void renderMask(
            final ByteProcessor bp,
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel);

    /**
     * Get a mask at a specific mipmap-level rendered with a specified mesh
     * cell size and minimum mesh cell size.  The returned
     * {@link ByteProcessor} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return mask with alpha transparencies [0,1] encoded as uint8 [0,255].
     */
    public ByteProcessor getMask(
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel);

    /**
     * Get a mask at a specific mipmap-level rendered with a specified mesh
     * cell size and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.  The returned
     * {@link ByteProcessor} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return mask with alpha transparencies [0,1] encoded as uint8 [0,255].
     */
    public ByteProcessor getMask(
            final double meshCellSize,
            final int mipmapLevel);

    /**
     * Get a mask at mipmap-level 0 rendered with a specified mesh cell size
     * and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.  The returned
     * {@link ByteProcessor} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return mask with alpha transparencies [0,1] encoded as uint8 [0,255].
     */
    public ByteProcessor getMask(final double meshCellSize);

    /**
     * Get a mask at mipmap-level 0 rendered with
     * {@link #DEFAULT_MESH_CELL_SIZE} and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.
     * The returned {@link ByteProcessor} may be generated or an existing
     * instance.  It is thus generally not safe to write into it.
     *
     * @return mask with alpha transparencies [0,1] encoded as uint8 [0,255].
     */
    public ByteProcessor getMask();




    /**
     * Render an ARGB image at a specific mipmap-level rendered with a
     * specified mesh cell size and minimum mesh cell size into an externally
     * provided {@link BufferedImage}.  The dimensions of the
     * {@link BufferedImage} must match or exceed those of this {@link Canvas},
     * tested only by assertion.
     */
    public void renderARGB(
            final BufferedImage image,
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel);

    /**
     * Get an ARGB image at a specific mipmap-level rendered with a specified
     * mesh cell size and minimum mesh cell size.  The returned
     * {@link Image} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return ARGB image
     */
    public Image getARGB(final double meshCellSize, final double minMeshCellSize, final int mipmapLevel);

    /**
     * Get an ARGB image at a specific mipmap-level rendered with a specified
     * mesh cell size and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.  The returned
     * {@link Image} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return ARGB image
     */
    public Image getARGB(final double meshCellSize, final int mipmapLevel);

    /**
     * Get an ARGB image at mipmap-level 0 rendered with
     * a specified mesh cell size and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.
     * The returned {@link Image} may be generated or an existing
     * instance.  It is thus generally not safe to write into it.
     *
     * @return ARGB image
     */
    public Image getARGB(final double meshCellSize);

    /**
     * Get an ARGB image at mipmap-level 0 rendered with
     * {@link #DEFAULT_MESH_CELL_SIZE} and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.
     * The returned {@link Image} may be generated or an existing instance.  It
     * is thus generally not safe to write into it.
     *
     * @return ARGB image with alpha transparencies [0,1] encoded as uint8 [0,255].
     */
    public Image getARGB();


    /**
     * Render a color channel in float precision at a specific mipmap-level
     * with a specified mesh cell size and minimum mesh cell size into an
     * externally provided {@link FloatProcessor}.  The dimensions of the
     * {@link FloatProcessor} must match or exceed those of this
     * {@link Canvas}, tested only by assertion.
     */
    public void renderFloatChannel(
            final FloatProcessor fp,
            final int channel,
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel);

    /**
     * Get a color channel in float precision at a specific mipmap-level
     * rendered with a specified mesh cell size and minimum mesh cell size.
     * The returned {@link FloatProcessor} may be generated or an existing
     * instance.  It is thus generally not safe to write into it.
     *
     * @return color channel
     */
    public FloatProcessor getFloatChannel(
            final int channel,
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel);

    /**
     * Get a color channel in float precision at a specific mipmap-level
     * rendered with a specified mesh cell size and
     * {@link #DEFAULT_MIN_MESH_CELL_SIZE}.  The returned
     * {@link FloatProcessor} may be generated or an existing instance.  It is
     * thus generally not safe to write into it.
     *
     * @return color channel
     */
    public FloatProcessor getFloatChannel(
            final int channel,
            final double meshCellSize,
            final int mipmapLevel);

    /**
     * Get a color channel in float precision at mipmap-level 0 rendered with
     * a specified mesh cell size and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.
     * The returned {@link FloatProcessor} may be generated or an existing
     * instance.  It is thus generally not safe to write into it.
     *
     * @return color channel
     */
    public FloatProcessor getFloatChannel(
            final int channel,
            final double meshCellSize);

    /**
     * Get a color channel in float precision at mipmap-level 0 rendered with
     * {@link #DEFAULT_MESH_CELL_SIZE} and {@link #DEFAULT_MIN_MESH_CELL_SIZE}.
     * The returned {@link FloatProcessor} may be generated or an existing
     * instance.  It is thus generally not safe to write into it.
     *
     * @return color channel
     */
    public FloatProcessor getFloatChannel(final int channel);



}
