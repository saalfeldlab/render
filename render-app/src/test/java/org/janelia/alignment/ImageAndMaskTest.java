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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@link ImageAndMask} class.
 *
 * @author Eric Trautman
 */
public class ImageAndMaskTest {

    @ParameterizedTest(name = "imageUrl={0}")
    @NullSource                                    // null image url
    @ValueSource(strings = {
            "scheme-with-invalid-@-char://test",   // invalid image url
            "file:///missing-file",                // missing image file with scheme
            "/missing-file"                        // missing image file without scheme
    })
    public void testValidateWithInvalidImage(final String imageUrl) {
        final ImageAndMask imageAndMask = new ImageAndMask(imageUrl, null);
        assertThrows(IllegalArgumentException.class, () -> imageAndMask.validate());
    }

}
