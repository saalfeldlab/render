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
package org.janelia.alignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeping this deprecated class around just in case there are scripts lying around that reference it.
 * It's main method now simply calls {@link ArgbRenderer#renderUsingCommandLineArguments}.
 *
 * @deprecated use {@link ArgbRenderer} instead.
 */
@Deprecated
public class Render {

    private static final Logger LOG = LoggerFactory.getLogger(Render.class);

    public static void main(final String[] args) {

        try {
            ArgbRenderer.renderUsingCommandLineArguments(args);
        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }

    }

}
