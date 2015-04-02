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



/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public interface MultiChannelScaleLevel<K, T> extends Bounded {

    public T getChannel(final K key);

    /**
     * Get the scale level index <em>s</em> relative to world scale that this
     * Channels object represents.  The corresponding scale factor is
     * 1 / 2<sup><em>s</em></sup>.  Pixel-center coordinates <em>x</em> reflect
     * world coordinates
     * <em>y</em> = 2<sup><em>s</em></sup><em>x</em> + 2<sup>(<em>s</em>-1)</sup> - 0.5
     * .
     *
     * The fastest averaging downsampling scheme, where 2<sup><em>n</em></sup>
     * <em>n</em>-dimensional pixels at scale level <em>s</em> are averaged to
     * generate scale level <em>s</em>+1, complies with this convention.
     *
     * <pre>
     * 0      1    2
     * #### > ## > #
     * ####   ##
     * ####
     * ####
     * </pre>
     *
     * @return scale level index
     */
    public int getScaleIndex();

    public MultiChannelScaleLevel<K, T> downsample();

    public MultiChannelScaleLevel<K, T> downsample(final int steps);
}
