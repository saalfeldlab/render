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
abstract public class Abstract2DMultiChannelScaleLevel<K, T> extends AbstractMultiChannelScaleLevel<K, T> {

    public Abstract2DMultiChannelScaleLevel() {
        super(2);
    }

    @Override
    public void bounds(
            @SuppressWarnings("hiding") final double[] min,
            @SuppressWarnings("hiding") final double[] max) {

        assert min.length >= 2 && max.length >= 2 : "Passed array is too short.";

        min[0] = this.min[0];
        min[1] = this.min[1];

        max[0] = this.max[0];
        max[1] = this.max[1];
    }
}
