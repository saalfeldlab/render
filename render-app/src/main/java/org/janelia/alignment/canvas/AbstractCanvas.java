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

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.awt.Image;
import java.awt.geom.Rectangle2D;

import mpicbg.models.CoordinateTransform;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractCanvas<T extends CoordinateTransform> implements Canvas<T> {

    final protected double width;
    final protected double height;

    public AbstractCanvas(final double width, final double height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public double getWidth() {
        return width;
    }

    @Override
    public double getHeight() {
        return height;
    }

    @Override
    public Rectangle2D.Double getMinBounds(final double meshCellSize, final Rectangle2D.Double preallocated) {
        final Rectangle2D.Double box = preallocated == null ? new Rectangle2D.Double() : preallocated;
        box.x = 0;
        box.y = 0;
        box.width = width;
        box.height = height;
        return box;
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize, final int mipmapLevel) {
        return getMask(meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, mipmapLevel);
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize) {
        return getMask(meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }

    @Override
    public ByteProcessor getMask() {
        return getMask(DEFAULT_MESH_CELL_SIZE, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }


    @Override
    public Image getARGB(final double meshCellSize, final int mipmapLevel) {
        return getARGB(meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, mipmapLevel);
    }

    @Override
    public Image getARGB(final double meshCellSize) {
        return getARGB(meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }

    @Override
    public Image getARGB() {
        return getARGB(DEFAULT_MESH_CELL_SIZE, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }


    @Override
    public FloatProcessor getFloatChannel(final int channel, final double meshCellSize, final int mipmapLevel) {
        return getFloatChannel(channel, meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, mipmapLevel);
    }

    @Override
    public FloatProcessor getFloatChannel(final int channel, final double meshCellSize) {
        return getFloatChannel(channel, meshCellSize, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }

    @Override
    public FloatProcessor getFloatChannel(final int channel) {
        return getFloatChannel(channel, DEFAULT_MESH_CELL_SIZE, DEFAULT_MIN_MESH_CELL_SIZE, 0);
    }
}
