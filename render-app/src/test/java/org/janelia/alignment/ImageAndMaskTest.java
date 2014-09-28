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

import org.junit.Test;

/**
 * Tests the {@link ImageAndMask} class.
 *
 * @author Eric Trautman
 */
public class ImageAndMaskTest {

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithNullImage() throws Exception {
        ImageAndMask imageAndMask = new ImageAndMask();
        imageAndMask.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithInvalidImageUrl() throws Exception {
        ImageAndMask imageAndMask = new ImageAndMask("scheme-with-invalid-@-char://test", null);
        imageAndMask.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithMissingImageFileWithScheme() throws Exception {
        ImageAndMask imageAndMask = new ImageAndMask("file:///missing-file", null);
        imageAndMask.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithMissingImageFileWithoutScheme() throws Exception {
        ImageAndMask imageAndMask = new ImageAndMask("/missing-file", null);
        imageAndMask.validate();
    }

}
