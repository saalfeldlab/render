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
package org.janelia.alignment.spec.stack;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link StackId} class.
 *
 * @author Eric Trautman
 */
public class StackIdTest {

    @Test
    public void testNameValidation() throws Exception {

        final String[][] validNames = {
                { "flyTEM",            "FAFB00",   "v12" },
                { "flyTEM",            "FAFB00",   "v10_acquire_LC_merged" },
                { "a_b_c_d_e_f_g_h_i", "jk_lm_no", "pq_rs_tu_vw_xyz" },
                // render.[8-24]__[27-30]__[33-107]__transform(110-118)
                { "89012345678901234", "7890",     "345678901234567890123456789012345678901234567890123456789012345678901234567"}
        };

        String owner;
        String project;
        String stack;
        for (final String[] testData : validNames) {
            owner = testData[0];
            project = testData[1];
            stack = testData[2];
            try {
                new StackId(owner, project, stack);
            } catch (final IllegalArgumentException e) {
                Assert.fail("valid name failed check " + e);
            }
        }

        final String[][] invalidNames = {
                { "flyTEM__2",         "FAFB00",   "v12" },
                { "flyTEM",            "FA__00",   "v12" },
                { "flyTEM",            "FAFB00",   "v12__acquire" },
                // render.[8-24]__[27-30]__[33-108]__transform(111-119)
                { "89012345678901234", "7890",     "3456789012345678901234567890123456789012345678901234567890123456789012345678"}
        };

        StackId stackId;
        for (final String[] testData : invalidNames) {
            owner = testData[0];
            project = testData[1];
            stack = testData[2];
            try {
                stackId = new StackId(owner, project, stack);
                Assert.fail("invalid id " + stackId + " did not cause exception");
            } catch (final IllegalArgumentException e) {
                Assert.assertTrue(true); // test passed
            }
        }

    }

}
