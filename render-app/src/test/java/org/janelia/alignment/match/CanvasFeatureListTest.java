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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imagefeatures.Feature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link CanvasFeatureList} class.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureListTest {

    @TempDir
    Path tempDir;

    private File rootFeatureListDirectory;

    @BeforeEach
    public void setUp() {
        rootFeatureListDirectory = tempDir.toFile();
    }

    @Test
    public void testWriteRead() throws Exception {

        final List<Feature> featureList = new ArrayList<>();
        featureList.add(new Feature(0.1, 0.2, new double[] {0.3, 0.4}, new float[] {0.5f, 0.6f}));

        final CanvasId canvasId = new CanvasId("testGroup", "testId", MontageRelativePosition.LEFT);

        final CanvasFeatureList canvasFeatureList = new CanvasFeatureList(canvasId,
                                                                          "http://foo.com/render-parameters",
                                                                          0.4,
                                                                          200,
                                                                          null,
                                                                          featureList);

        CanvasFeatureList.writeToStorage(rootFeatureListDirectory, canvasFeatureList);

        final CanvasFeatureList storedCanvasFeatureList =
                CanvasFeatureList.readFromStorage(rootFeatureListDirectory, canvasId);

        assertEquals(canvasId, storedCanvasFeatureList.getCanvasId(),
                     "invalid stored canvasId");
        assertEquals(featureList.size(), storedCanvasFeatureList.getFeatureList().size(),
                     "invalid number of stored features");
    }
}
