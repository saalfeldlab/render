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

import mpicbg.trakem2.transform.AffineModel2D;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link Transform} class.
 *
 * @author Eric Trautman
 */
public class TransformTest {

    @Test
    public void testCreateTransformWithValidation() throws Exception {
        final Transform transform = new Transform(AffineModel2D.class.getName(), "1 0 0 1 0 0");
        transform.validate();
        mpicbg.models.CoordinateTransform coordinateTransform = transform.createTransform();
        Assert.assertNotNull("transform not created after validation", coordinateTransform);
    }

    @Test
    public void testCreateTransformWithoutValidation() throws Exception {
        final Transform transform = new Transform(AffineModel2D.class.getName(), "1 0 0 1 0 0");
        mpicbg.models.CoordinateTransform coordinateTransform = transform.createTransform();
        Assert.assertNotNull("transform not created prior to validation", coordinateTransform);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithUnknownClass() throws Exception {
        final Transform transform = new Transform("bad-class", "1 0 0 1 0 0");
        transform.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithNonTransformClass() throws Exception {
        final Transform transform = new Transform(this.getClass().getName(), "1 0 0 1 0 0");
        transform.validate();
    }

}
