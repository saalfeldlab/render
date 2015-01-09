package org.janelia.perf;

import ij.ImagePlus;
import org.janelia.alignment.Utils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * All test files can be found under src/test/resources/perf-test/.
 * All test images were created from level_0/col0060_row0140_cam0.tif using ImageMagick.
 * Details on the creation process can be found in the gen-mipmaps.sh script.
 * </p>
 *
 * <p>
 * Initial results from Mac OS 10.7.5 with SSD file system:
 * </p>
 *
 * <pre>
 * level  format  file length  test     elapsedTime
 * -----  ------  -----------  -------  -----------
 *     1     tif      1384170  avg(5)             4
 *     1     pgm      1382417  avg(5)             7
 *     1     png      1193849  avg(5)            24
 *     1     jpg       482890  avg(5)            61
 *
 *     2     tif       346290  avg(5)             1
 *     2     pgm       345615  avg(5)             3
 *     2     png       301993  avg(5)             7
 *     2     jpg       156144  avg(5)            41
 *
 *     3     pgm        86415  avg(5)             1
 *     3     tif        86818  avg(5)             1
 *     3     png        74978  avg(5)             2
 *     3     jpg        41649  avg(5)            34
 * </pre>
 *
 * @author Eric Trautman
 */
public class ImageLoadPerformanceTest {

    private boolean enableTests;
    private int numberOfTimesToRepeatEachTest;

    private String[] formats = { "jpg", "pgm", "png", "tif" };

    private PerformanceTestData.TestResults<TestData> testResults;
    private List<TestData> testDataList;

    public static void main(String[] args) {
        ImageLoadPerformanceTest test = new ImageLoadPerformanceTest();
        try {
            test.setup();
            test.enableTests = true;
            test.runTests();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Before
    public void setup() throws Exception {
        enableTests = false; // set this to true to enable tests - normally, there is no need to run them
        numberOfTimesToRepeatEachTest = 5;
        createAndOrderTests();

        // the first usage of ImagePlus sometimes incurs a significant performance penalty,
        // so open a different file for each format here before starting the tests
        for (String format : formats) {
            Utils.openImagePlus("src/test/resources/perf-test/image-plus-start/start." + format);
        }
    }

    @Test
    public void runTests() throws Exception {
        if (enableTests) {
            for (TestData testData : testDataList) {
                runTest(testData);
            }
            testResults.collateAndPrintTimes(testDataList);
        }
    }

    private void runTest(TestData testData) {

        // *** Start Clock ***
        testData.setStartTime();

        final ImagePlus imagePlus = Utils.openImagePlus(testData.file.getAbsolutePath());

        // *** Stop Clock ***
        testData.calculateElapsedTime();

        // make sure image plus didn't quietly fail
        Assert.assertNotNull("null imagePlus object returned for " + testData.file, imagePlus);
    }

    private void createAndOrderTests() {

        testDataList = new ArrayList<TestData>();

        final String baseImagePath = "src/test/resources/perf-test/mipmaps/";

        File file;
        for (int testNumber = 0; testNumber < numberOfTimesToRepeatEachTest; testNumber++) {
            for (int level = 1; level < 4; level++) {
                for (String format : formats) {
                    final String fileName = "/col0060_row0140_cam0.tif_level_" + level + "_mipmap." + format;
                    file = new File(baseImagePath + format + fileName);
                    testDataList.add(new TestData(file, format, level, String.valueOf(testNumber)));
                }
            }
        }

        testResults = new PerformanceTestData.TestResults<TestData>() {

            @Override
            public TestData getAverageInstance(TestData groupInstance,
                                               long averageElapsedTime,
                                               int numberOfTests) {

                TestData averageInstance = new TestData(groupInstance.file,
                                                        groupInstance.format,
                                                        groupInstance.level,
                                                        "avg(" + numberOfTests + ")");
                averageInstance.setElapsedTime(averageElapsedTime);
                return averageInstance;
            }

            @Override
            public String getReportHeader(String reportName) {
                final String headerFormat = "%5s  %6s  %11s  %-7s  %11s";
                return String.format(headerFormat, "level", "format", "file length", "test   ", "elapsedTime") + "\n" +
                       String.format(headerFormat, "-----", "------", "-----------", "-------", "-----------");
            }

            @Override
            public String formatTestResult(TestData result) {
                return String.format("%5d  %6s  %11d  %-7s  %11d",
                                     result.level,
                                     result.format,
                                     result.file.length(),
                                     result.test,
                                     result.getElapsedTime());
            }

            @Override
            public Map<String, Comparator<TestData>> getReportNameToComparatorMap() {
                Map<String, Comparator<TestData>> map =
                        new LinkedHashMap<String, Comparator<TestData>>();
                map.put("Level::Threads Results", levelTimeComparator);
                return map;
            }

            private final Comparator<TestData> levelTimeComparator =
                    new Comparator<TestData>() {
                        @Override
                        public int compare(TestData o1,
                                           TestData o2) {
                            int result = o1.level - o2.level;
                            if (result == 0) {
                                result = (int) (o1.getElapsedTime() - o2.getElapsedTime());
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
                    };
        };
    }

    public class TestData extends PerformanceTestData {

        private File file;
        private String format;
        private int level;
        private String test;

        public TestData(File file,
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

        @Override
        public String getAverageGroup() {
            return level + "::" + format;
        }

        @Override
        public String getReportGroup() {
            return String.valueOf(level);
        }

    }

}
