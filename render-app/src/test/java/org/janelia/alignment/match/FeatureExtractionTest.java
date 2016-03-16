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
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs feature extraction for center and edge tiles, logging results and performance.
 * Intent is to support tuning of SIFT parameters to optimize tile feature extraction time.
 *
 * Uncomment Ignore annotation below to run tests using JUnit.
 *
 * @author Eric Trautman
 */
@Ignore
public class FeatureExtractionTest {

    private String testResourcePath;

    private CanvasFeatureExtractor canvasFeatureExtractor;
    private List<Feature> featureList;

    private boolean saveTileImage;
    private boolean printFeatureList;

    @Before
    public void setup() throws Exception {

        testResourcePath = "src/test/resources/match-test";

        final FloatArray2DSIFT.Param coreSiftParameters = new FloatArray2DSIFT.Param();

        // tune here ...
        coreSiftParameters.fdSize = 4;
        coreSiftParameters.steps = 3;
        final double minScale = 0.1;
        final double maxScale = 0.4;

        canvasFeatureExtractor = new CanvasFeatureExtractor(coreSiftParameters,
                                                            minScale,
                                                            maxScale,
                                                            true);

        saveTileImage = false;    // set this to true if you want to see the generated tile image
        printFeatureList = false; // set this to true if you want to see list of features

        featureList = null;
    }

    @After
    public void tearDown() throws Exception {

        if ((featureList != null) && (featureList.size() > 0)) {

            final FeatureSorter sorter = new FeatureSorter();
            Collections.sort(featureList, sorter.comparator);
            if (printFeatureList) {
                LOG.info(sorter.formatList(featureList));
            }
            LOG.info("{} out of {} features were the same", sorter.sameCount, featureList.size());

        } else {
            LOG.warn("features were NOT extracted");
        }

    }

    @Test
    public void testCenterTile() throws Exception {

        LOG.info("\n\n***** Test Center Tile *****\n");

        final RenderParameters tileRenderParameters = RenderParameters.parseJson(getCenterTileJson());
        tileRenderParameters.initializeDerivedValues();

        featureList = canvasFeatureExtractor.extractFeatures(tileRenderParameters,
                                                             getRenderFile(tileRenderParameters));
    }

    @Test
    public void testEdgeTile() throws Exception {

        LOG.info("\n\n***** Test Edge Tile *****\n");

        final RenderParameters tileRenderParameters = RenderParameters.parseJson(getEdgeTileJson());
        tileRenderParameters.initializeDerivedValues();

        featureList = canvasFeatureExtractor.extractFeatures(tileRenderParameters,
                                                             getRenderFile(tileRenderParameters));
    }

    private File getRenderFile(final RenderParameters renderParameters) {
        File renderFile = null;
        if (saveTileImage) {
            renderFile = new File(renderParameters.getTileSpecs().get(0).getTileId() + ".png");
        }
        return renderFile;
    }

    private String getCenterTileJson() {
        return "{\n" +
               "  \"x\" : 168900.0,\n" +
               "  \"y\" : 146000.0,\n" +
               "  \"width\" : 2760,\n" +
               "  \"height\" : 2330,\n" +
               "  \"scale\" : 0.4,\n" +
               "  \"tileSpecs\" : [ {\n" +
               "    \"tileId\" : \"141215105451090080.5489.0\",\n" +
               "    \"z\" : 5489.0,\n" +
               "    \"minX\" : 168900.0,\n" +
               "    \"minY\" : 146000.0,\n" +
               "    \"maxX\" : 171659.0,\n" +
               "    \"maxY\" : 148329.0,\n" +
               "    \"width\" : 2760.0,\n" +
               "    \"height\" : 2330.0,\n" +
               "    \"mipmapLevels\": {\n" +
               "      \"0\": {\n" +
               "        \"imageUrl\" : \"" + testResourcePath + "/col0090_row0080_cam1.png\",\n" +
               "        \"maskUrl\" : \"" + testResourcePath + "/cam_1_mask.png\"\n" +
               "      }\n" +
               "    },\n" +
               "    \"transforms\": {\n" +
               "      \"type\": \"list\",\n" +
               "      \"specList\": [\n" +
               "        {\n" +
               "          \"type\": \"leaf\",\n" +
               "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
               "        \"dataString\" : \"1.0 0.0 0.0 1.0 168900 146000\"\n" +
               "      } ]\n" +
               "    }\n" +
               "  } ]\n" +
               "}";
    }

    private String getEdgeTileJson() {
        return "{\n" +
               "  \"x\" : 123900.0,\n" +
               "  \"y\" : 186200.0,\n" +
               "  \"width\" : 2760,\n" +
               "  \"height\" : 2330,\n" +
               "  \"scale\" : 0.4,\n" +
               "  \"tileSpecs\" : [ {\n" +
               "    \"tileId\" : \"141215105451066102.5489.0\",\n" +
               "    \"z\" : 5489.0,\n" +
               "    \"minX\" : 123900.0,\n" +
               "    \"minY\" : 186200.0,\n" +
               "    \"maxX\" : 126659.0,\n" +
               "    \"maxY\" : 188529.0,\n" +
               "    \"width\" : 2760.0,\n" +
               "    \"height\" : 2330.0,\n" +
               "    \"mipmapLevels\" : {\n" +
               "      \"0\" : {\n" +
               "        \"imageUrl\" : \"" + testResourcePath + "/col0066_row0102_cam3.png\",\n" +
               "        \"maskUrl\" : \"" + testResourcePath + "/cam_3_mask.png\"\n" +
               "      }\n" +
               "    },\n" +
               "    \"transforms\" : {\n" +
               "      \"type\" : \"list\",\n" +
               "      \"specList\" : [ {\n" +
               "        \"type\" : \"leaf\",\n" +
               "        \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
               "        \"dataString\" : \"1.0 0.0 0.0 1.0 123900 186200\"\n" +
               "      } ]\n" +
               "    }\n" +
               "  } ]\n" +
               "}";
    }

    private class FeatureSorter {

        public int sameCount = 0;

        public Comparator<Feature> comparator = new Comparator<Feature>() {

            @Override
            public int compare(final Feature o1,
                               final Feature o2) {
                double dResult = 0.0;
                for (int i = 0; i < o1.location.length; i++) {
                    if (i < o2.location.length) {
                        dResult = o1.location[i] - o2.location[i];
                        if (dResult != 0) {
                            break;
                        }

                    }
                }

                final int result;
                if (dResult > 0) {
                    result = 1;
                } else if (dResult < 0) {
                    result = -1;
                } else {
                    result = o1.location.length - o2.location.length;
                    if (result == 0) {
                        sameCount++;
                    }
                }

                return result;
            }
        };

        public String formatList(final List<Feature> featureList) {
            final StringBuilder sb = new StringBuilder(featureList.size() * 60);
            final Formatter formatter = new Formatter(sb);
            sb.append('\n');
            for (int i = 0; i < featureList.size(); i++) {
                formatter.format("[%5d] => (", i);
                for (final double coordinate : featureList.get(i).location) {
                    formatter.format("%15.10f, ", coordinate);
                }
                sb.replace(sb.length() - 2, sb.length(), ")\n");
            }
            return sb.toString();
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(FeatureExtractionTest.class);

}
