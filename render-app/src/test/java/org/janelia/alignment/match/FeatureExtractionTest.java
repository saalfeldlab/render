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

import ij.ImagePlus;
import ij.process.ByteProcessor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.util.Timer;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.util.ImageProcessorCache;
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
    private FloatArray2DSIFT.Param siftParameters;
    private double minScale;
    private double maxScale;

    private List<Feature> featureList;

    private boolean saveTileImage;
    private boolean printFeatureList;

    @Before
    public void setup() throws Exception {

        testResourcePath = "src/test/resources/match-test";
        featureList = new ArrayList<>();

        siftParameters = new FloatArray2DSIFT.Param();

        // tune here ...
        siftParameters.fdSize = 4;
        siftParameters.steps = 3;
        minScale = 0.1;
        maxScale = 0.4;

        saveTileImage = false;    // set this to true if you want to see the generated tile image
        printFeatureList = false; // set this to true if you want to see list of features
    }

    @After
    public void tearDown() throws Exception {
        final FeatureSorter sorter = new FeatureSorter();
        Collections.sort(featureList, sorter.comparator);
        if (printFeatureList) {
            LOG.info(sorter.formatList(featureList));
        }
        LOG.info("{} out of {} features were the same", sorter.sameCount, featureList.size());
    }

    @Test
    public void testCenterTile() throws Exception {

        LOG.info("\n\n***** Test Center Tile *****\n");

        final RenderParameters tileRenderParameters = RenderParameters.parseJson(getCenterTileJson());
        tileRenderParameters.initializeDerivedValues();
        final BufferedImage bufferedImage = loadImage(tileRenderParameters);

        featureList = extractFeatures(bufferedImage, siftParameters, minScale, maxScale);
    }

    @Test
    public void testEdgeTile() throws Exception {

        LOG.info("\n\n***** Test Edge Tile *****\n");

        final RenderParameters tileRenderParameters = RenderParameters.parseJson(getEdgeTileJson());
        tileRenderParameters.initializeDerivedValues();
        final BufferedImage bufferedImage = loadImage(tileRenderParameters);

        featureList = extractFeatures(bufferedImage, siftParameters, minScale, maxScale);
    }

    private List<Feature> extractFeatures(final BufferedImage bufferedImage,
                                          final FloatArray2DSIFT.Param siftParameters,
                                          final double minScale,
                                          final double maxScale) throws IllegalStateException {

        LOG.info("extractFeatures: entry");

        final Timer timer = new Timer();
        timer.start();

        final FloatArray2DSIFT.Param localSiftParameters = siftParameters.clone();
        final int w = bufferedImage.getWidth();
        final int h = bufferedImage.getHeight();
        final int minSize = w < h ? w : h;
        final int maxSize = w > h ? w : h;
        localSiftParameters.minOctaveSize = (int)(minScale * minSize - 1.0);
        localSiftParameters.maxOctaveSize = (int)Math.round(maxScale * maxSize);

        LOG.info("extractFeatures: fdSize={}, steps={}, minScale={}, maxScale={}, minOctaveSize={}, maxOctaveSize={}",
                 localSiftParameters.fdSize,
                 localSiftParameters.steps,
                 minScale,
                 maxScale,
                 localSiftParameters.minOctaveSize,
                 localSiftParameters.maxOctaveSize);

        // Let imagePlus determine correct processor - original use of ColorProcessor resulted in
        // fewer extracted features when bufferedImage was loaded from disk.
        final ImagePlus imagePlus = new ImagePlus("", bufferedImage);

        final FloatArray2DSIFT sift = new FloatArray2DSIFT(localSiftParameters);
        final SIFT ijSIFT = new SIFT(sift);

        final List<Feature> featureList = new ArrayList<>();
        ijSIFT.extractFeatures(imagePlus.getProcessor(), featureList);

        if (featureList.size() == 0) {

            final StringBuilder sb = new StringBuilder(256);
            sb.append("no features were extracted");

            if (bufferedImage.getWidth() < siftParameters.minOctaveSize) {
                sb.append(" because montage image width (").append(bufferedImage.getWidth());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (bufferedImage.getHeight() < siftParameters.minOctaveSize) {
                sb.append(" because montage image height (").append(bufferedImage.getHeight());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (bufferedImage.getWidth() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image width (").append(bufferedImage.getWidth());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else if (bufferedImage.getHeight() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image height (").append(bufferedImage.getHeight());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else {
                sb.append(", not sure why, montage image width (").append(bufferedImage.getWidth());
                sb.append(") or height (").append(bufferedImage.getHeight());
                sb.append(") may be less than maxKernelSize derived from SIFT steps(");
                sb.append(siftParameters.steps).append(")");
            }

            throw new IllegalStateException(sb.toString());
        }

        LOG.info("extractFeatures: exit, extracted " + featureList.size() +
                 " features, elapsedTime=" + timer.stop() + "ms");

        return featureList;
    }

    private BufferedImage loadImage(final RenderParameters tileRenderParameters) {

        LOG.info("loadImage: entry");

        final Timer timer = new Timer();
        timer.start();

        final BufferedImage bufferedImage = tileRenderParameters.openTargetImage();
        final ByteProcessor ip = new ByteProcessor(bufferedImage.getWidth(), bufferedImage.getHeight());

        mpicbg.ij.util.Util.fillWithNoise(ip);
        bufferedImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);

        Render.render(tileRenderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        if (saveTileImage) {
            final String fileName = tileRenderParameters.getTileSpecs().get(0).getTileId() + ".png";
            try {
                Utils.saveImage(bufferedImage, fileName, Utils.PNG_FORMAT, false, 0.85f);
            } catch (final IOException e) {
                LOG.error("failed to save " + fileName, e);
            }
        }

        LOG.info("loadImage: exit, elapsedTime=" + timer.stop() + "ms");

        return bufferedImage;
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
