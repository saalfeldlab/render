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
import java.awt.image.BufferedImage;
import java.util.TreeMap;

import mpicbg.models.IdentityModel;

import org.janelia.alignment.ImageAndMask;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Mipmaps extends AbstractCanvas<IdentityModel> {

    final protected TreeMap<Integer, ImageAndMask> mipmapLevels;
    final protected transient TreeMap<Integer, ByteProcessor> maskLevels;
    final protected transient TreeMap<Integer, BufferedImage> argbLevels;
    final protected transient TreeMap<Integer, FloatProcessor> floatLevels;
    
    public Mipmaps() {
        this.mipmapLevels = new TreeMap<Integer, ImageAndMask>();
    }

    @Override
    public void renderMask(
            final ByteProcessor bp,
            final double meshCellSize,
            final double minMeshCellSize,
            final int mipmapLevel) {
        
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ByteProcessor getMask(final double meshCellSize) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ByteProcessor getMask() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void renderARGB(final BufferedImage image, final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub

    }

    @Override
    public Image getARGB(final double meshCellSize, final double minMeshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Image getARGB(final double meshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Image getARGB(final double meshCellSize) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Image getARGB() {
        // TODO Auto-generated method stub
        return null;
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
    public FloatProcessor getFloatChannel(final int channel, final double meshCellSize, final int mipmapLevel) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public FloatProcessor getFloatChannel(final int channel, final double meshCellSize) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public FloatProcessor getFloatChannel(final int channel) {
        // TODO Auto-generated method stub
        return null;
    }

}
