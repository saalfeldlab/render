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

import java.util.HashMap;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractMultiChannelScaleLevel<K, T> implements MultiChannelScaleLevel<K, T> {

    protected HashMap<K, T> channels;
    final protected double[] min;
    final protected double[] max;
    protected int scaleLevelIndex = 0;

    public AbstractMultiChannelScaleLevel(final int numDimensions) {
        min = new double[numDimensions];
        max = new double[numDimensions];
    }

    @Override
    public void bounds(
            @SuppressWarnings("hiding") final double[] min,
            @SuppressWarnings("hiding") final double[] max) {

        assert
            min.length >= this.min.length &&
            max.length >= this.max.length : "Passed array is too short.";

        System.arraycopy(this.min, 0, min, 0, this.min.length);
        System.arraycopy(this.max, 0, max, 0, this.max.length);
    }

    @Override
    public int getScaleIndex() {
        return scaleLevelIndex;
    }

    @Override
    public T getChannel(final K channel) {
        return channels.get(channel);
    }
}
