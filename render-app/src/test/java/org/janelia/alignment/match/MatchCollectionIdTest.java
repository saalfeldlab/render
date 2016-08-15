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
package org.janelia.alignment.match;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MatchCollectionId} class.
 *
 * @author Eric Trautman
 */
public class MatchCollectionIdTest {

    @Test
    public void testNameValidation() throws Exception {

        final String[][] validNames = {
                { "flyTEM",            "FAFBv12" },
                { "flyTEM",            "FAFBv12Test10" },
                { "flyTEM",            "v12_SURF" },
                { "flyTEM",            "v12_dmesh" },
                { "trautmane",         "test_match_gen" },
                { "flyTEM",            "FAFBv12_sections_8_9" },
                { "a_b_c_d_e_f_g_h_i", "jk_lm_no_pq_rs_tu_vw_xyz" },
                // match.[7-23]__[26-118]
                { "78901234567890123", "678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"}
        };

        String owner;
        String name;
        for (final String[] testData : validNames) {
            owner = testData[0];
            name = testData[1];
            try {
                new MatchCollectionId(owner, name);
            } catch (final IllegalArgumentException e) {
                Assert.fail("valid name failed check " + e);
            }
        }

        final String[][] invalidNames = {
                { "flyTEM__2",     "FAFBv12" },
                { "flyTEM",        "FAFBv12Test10__2" },
                { "flyTEM",        "v12_SURF_" },
                { "flyTEM",        "_v12_dmesh" },
                { "trautmane___a", "test_match_gen" },
                { "flyTEM",        "FAFBv12_sections__" },
                // match.[7-23]__[26-119]
                { "78901234567890123", "6789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789"}
        };

        MatchCollectionId matchCollectionId;
        for (final String[] testData : invalidNames) {
            owner = testData[0];
            name = testData[1];
            try {
                matchCollectionId = new MatchCollectionId(owner, name);
                Assert.fail("invalid id " + matchCollectionId + " did not cause exception");
            } catch (final IllegalArgumentException e) {
                Assert.assertTrue(true); // test passed
            }
        }

    }

}
