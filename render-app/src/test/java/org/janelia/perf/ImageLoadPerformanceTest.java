package org.janelia.perf;

import ij.ImagePlus;
import org.janelia.alignment.Utils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the {@link ImagePlus} load times for different image formats and mipmap levels.
 * Average times for each format and level, sorted by time and file size, are printed to standard out.
 *
 * <p>
 * The intent for these tests is to simply assess the relative performance for each format on a given file system.
 * File system caching will affect results, but should affect results for all formats equivalently.
 * </p>
 *
 * <p>
 * All test files can be found under src/test/resources/image-load-test/.
 * All test images were created from col0060_row0140_cam0.tif using ImageMagick.
 * Details on the creation process can be found in the gen-mipmaps.sh script.
 * </p>
 *
 * <p>
 * Initial results from Mac OS 10.7.5 with SSD file system:
 * </p>
 *
 * <pre>
 *     level  format  file length    test  loadTime
 *     -----  ------  -----------    ----  --------
 *         0     tif      5535650     avg         7
 *         0     pgm      5529617     avg        26
 *         0     png      4703213     avg        45
 *         0     jpg      1668499     avg       167
 *
 *         1     tif      1384130     avg         2
 *         1     png      1193849     avg        11
 *         1     pgm      1382417     avg        12
 *         1     jpg       484028     avg        61
 *
 *         2     tif       346250     avg         1
 *         2     pgm       345615     avg         2
 *         2     png       301993     avg         3
 *         2     jpg       156221     avg        40
 *
 *         3     pgm        86415     avg         0
 *         3     tif        86778     avg         0
 *         3     png        74978     avg         1
 *         3     jpg        41650     avg        34
 * </pre>
 *
 * @author Eric Trautman
 */
public class ImageLoadPerformanceTest {

    private boolean enableTests;
    private int numberOfLevels = 4; // 0 to 3
    private String[] formats = { "jpg", "pgm", "png", "tif" };

    private List<Map<String, List<TestStats>>> levelFormatToStatsMap;
    private List<TestStats> testStatsList;

    @Before
    public void setup() throws Exception {
        enableTests = false; // set this to true to enable tests - normally, there is no need to run them
        createLevelFormatToStatsMap();
        createAndOrderTests();
    }

    @Test
    public void runTests() throws Exception {
        if (enableTests) {
            for (TestStats testStats : testStatsList) {
                runTest(testStats);
            }
            calculateAndPrintAverageTimes();
        }
    }

    private void runTest(TestStats testStats) {
        final long startTime = System.currentTimeMillis();
        final ImagePlus imagePlus = Utils.openImagePlus(testStats.file.getAbsolutePath());
        testStats.deriveLoadTime(startTime);

        Assert.assertNotNull("null imagePlus object returned for " + testStats.getPath(), imagePlus);
    }

    private void createLevelFormatToStatsMap() {
        this.levelFormatToStatsMap = new ArrayList<Map<String, List<TestStats>>>();

        Map<String, List<TestStats>> formatToStatsMap;
        for (int level = 0; level < numberOfLevels; level++) {
            formatToStatsMap = new HashMap<String, List<TestStats>>();
            this.levelFormatToStatsMap.add(formatToStatsMap);
            for (String format : formats) {
                formatToStatsMap.put(format, new ArrayList<TestStats>());
            }
        }
    }

    /**
     * Tests are ordered as follows:
     *
     *   load each format at a given level,
     *   move the next level,
     *   repeat n times
     */
    private void createAndOrderTests() {
        //
        this.testStatsList = new ArrayList<TestStats>();

        final String baseImagePath = "src/test/resources/image-load-test/";
        final int testsPerFormatLevel = 5;

        Map<String, List<TestStats>> formatToStatsMap;
        File file;
        TestStats testStats;
        List<TestStats> statsListForLevelAndFormat;
        //noinspection ConstantConditions
        for (int testNumber = 0; testNumber < testsPerFormatLevel; testNumber++) {
            for (int level = 0; level < numberOfLevels; level++) {
                formatToStatsMap = this.levelFormatToStatsMap.get(level);
                for (String format : formats) {
                    file = new File(baseImagePath + format + "/level_" + level + '.' + format);
                    testStats = new TestStats(file, format, level, String.valueOf(testNumber));

                    this.testStatsList.add(testStats);

                    statsListForLevelAndFormat = formatToStatsMap.get(format);
                    statsListForLevelAndFormat.add(testStats);
                }
            }
        }

    }

    private void calculateAndPrintAverageTimes() {

        final List<TestStats> avgTestStatsList = new ArrayList<TestStats>();

        TestStats avgStats = null;
        for (int level = 0; level < levelFormatToStatsMap.size(); level++) {

            for (List<TestStats> statsListForLevelAndFormat : levelFormatToStatsMap.get(level).values()) {

                int sum = 0;

                for (TestStats testStats : statsListForLevelAndFormat) {
                    if (sum == 0) {
                        avgStats = new TestStats(testStats.file, testStats.format, level, "avg");
                    }
                    sum += testStats.loadTime;
                }

                //noinspection ConstantConditions
                avgStats.loadTime = sum / statsListForLevelAndFormat.size();

                avgTestStatsList.add(avgStats);
            }

        }

        Collections.sort(avgTestStatsList,
                         new Comparator<TestStats>() {
                             @Override
                             public int compare(TestStats o1,
                                                TestStats o2) {
                                 int result = o1.level - o2.level;
                                 if (result == 0) {
                                     result = (int) (o1.loadTime - o2.loadTime);
                                     if (result == 0) {
                                         result = (int) (o1.file.length() - o2.file.length());
                                         if (result == 0) {
                                             result = o1.format.compareTo(o2.format);
                                             if (result == 0) {
                                                 result = o1.test.compareTo(o2.test);
                                             }
                                         }
                                     }
                                 }
                                 return result;
                             }
                         });


        System.out.println(STATS_HEADER);

        int currentLevel = 0;
        for (TestStats testStats : avgTestStatsList) {
            if (testStats.level != currentLevel) {
                currentLevel = testStats.level;
                System.out.println();
            }
            System.out.println(testStats);
        }
    }

    private class TestStats {

        private File file;
        private String format;
        private int level;
        private String test;
        private long loadTime;

        public TestStats(File file,
                         String format,
                         int level,
                         String test) {
            if (! file.exists()) {
                throw new IllegalArgumentException(file.getAbsolutePath() + " not found");
            }
            this.file = file;
            this.format = format;
            this.level = level;
            this.test = test;
        }

        public String getPath() {
            return file.getAbsolutePath();
        }

        public void deriveLoadTime(long startTime) {
            this.loadTime = System.currentTimeMillis() - startTime;
        }

        @Override
        public String toString() {
            return String.format(STATS_FORMAT, level, format, file.length(), test, loadTime);
        }

    }

    private static final String STATS_FORMAT = "%5d  %6s  %11d  %6s  %8d";
    private static final String STATS_HEADER_FORMAT = STATS_FORMAT.replace('d','s');
    private static final String STATS_HEADER =
            String.format(STATS_HEADER_FORMAT, "level", "format", "file length", "test", "loadTime") + "\n" +
            String.format(STATS_HEADER_FORMAT, "-----", "------", "-----------", "----", "--------");
}
