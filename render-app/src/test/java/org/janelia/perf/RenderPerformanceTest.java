package org.janelia.perf;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the render times for different image formats, thread counts, and mipmap levels.
 * Sorted average times are printed to standard out.
 *
 * <p>
 * The intent for these tests is to simply assess the relative performance for each combination of factors.
 * File system caching will affect results, but should affect results for all formats equivalently.
 * </p>
 *
 * <p>
 * All test files can be found under src/test/resources/perf-test/.
 * All test image mipmaps were created using ImageMagick.
 * Details on the creation process can be found in the gen-mipmaps.sh script.
 * </p>
 *
 * <p>
 * Initial results from Mac OS 10.7.5 with SSD file system:
 * </p>
 *
 * <pre>
 *   level  format  threads  test     elapsedTime
 *   -----  ------  -------  -------  -----------
 *       1     tif        4  avg(3)           677
 *       1     tif        2  avg(3)           678
 *       1     tif        1  avg(3)           683
 *       1     jpg        4  avg(3)           951
 *       1     jpg        2  avg(3)           959
 *       1     jpg        1  avg(3)          1068
 *
 *       2     tif        4  avg(3)           666
 *       2     tif        2  avg(3)           668
 *       2     tif        1  avg(3)           669
 *       2     jpg        2  avg(3)           858
 *       2     jpg        4  avg(3)           860
 *       2     jpg        1  avg(3)           864
 *
 *       3     tif        4  avg(3)           663
 *       3     tif        2  avg(3)           665
 *       3     tif        1  avg(3)           667
 *       3     jpg        4  avg(3)           833
 *       3     jpg        1  avg(3)           835
 *       3     jpg        2  avg(3)           836
 * </pre>
 *
 * @author Eric Trautman
 */
public class RenderPerformanceTest {

    private boolean enableTests;
    private int numberOfTimesToRepeatEachTest;

    private final String[] formats = { "jpg", "tif" };

    private PerformanceTestData.TestResults<TestData> testResults;
    private List<TestData> testDataList;

    public static void main(final String[] args) {
        final RenderPerformanceTest test = new RenderPerformanceTest();
        try {
            test.setup();
            test.enableTests = true;
            test.runTests();
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    @Before
    public void setup() throws Exception {
        enableTests = false; // set this to true to enable tests - normally, there is no need to run them
        numberOfTimesToRepeatEachTest = 3;
        createAndOrderTests();

        // the first usage of ImagePlus sometimes incurs a significant performance penalty,
        // so open a different file for each format here before starting the tests
        for (final String format : formats) {
            Utils.openImagePlus("src/test/resources/perf-test/image-plus-start/start." + format);
        }

        // Render class logging added an average of 2 - 10 milliseconds to each test result.
        // Since it is useful to see the log data and the overhead is relatively small,
        // logging has been left enabled.
        // Simply uncomment the code below to disable logging.

        // ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
        //        org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        // root.setLevel(ch.qos.logback.classic.Level.OFF);
    }

    @Test
    public void runTests() throws Exception {
        if (enableTests) {
            for (final TestData testData : testDataList) {
                runTest(testData);
            }
            testResults.collateAndPrintTimes(testDataList);
        }
    }

    private void runTest(final TestData testData) {

        final RenderParameters params;
        final BufferedImage targetImage;

        try {
            params = RenderParameters.parseJson(testData.renderParametersFile);
            params.setScale(testData.getScale());

            params.initializeDerivedValues();
            params.validate();
            targetImage = params.openTargetImage();
        } catch (final Throwable t) {
            throw new RuntimeException("failed to load render parameters from " + testData.renderParametersFile, t);
        }

        // *** Start Clock ***
        testData.setStartTime();

        params.setNumberOfThreads(testData.threads);

        ArgbRenderer.render(params,
                            targetImage,
                            ImageProcessorCache.DISABLED_CACHE);

        // *** Stop Clock ***
        testData.calculateElapsedTime();
    }

    private void createAndOrderTests() {

        testDataList = new ArrayList<>();

        final String baseImagePath = "src/test/resources/perf-test/json/";
        final int availableProcessors = Runtime.getRuntime().availableProcessors();

        File file;
        for (int testNumber = 0; testNumber < numberOfTimesToRepeatEachTest; testNumber++) {
            for (int level = 1; level < 4; level++) {
                for (final String format : formats) {
                    final String fileName = "/render-1600-3200-1600-1600.json";
                    file = new File(baseImagePath + format + fileName);
                    for (int threadCount = 1; threadCount <= 4; threadCount = threadCount * 2) {
                        if (threadCount <= availableProcessors) {
                            testDataList.add(new TestData(file,
                                                          format,
                                                          level,
                                                          threadCount,
                                                          String.valueOf(testNumber)));
                        }
                    }
                }
            }
        }

        testResults = new PerformanceTestData.TestResults<TestData>() {

            @Override
            public TestData getAverageInstance(final TestData groupInstance,
                                               final long averageElapsedTime,
                                               final int numberOfTests) {

                final TestData averageInstance = new TestData(groupInstance.renderParametersFile,
                                                        groupInstance.format,
                                                        groupInstance.level,
                                                        groupInstance.threads,
                                                        "avg(" + numberOfTests + ")");
                averageInstance.setElapsedTime(averageElapsedTime);
                return averageInstance;
            }

            @Override
            public String getReportHeader(final String reportName) {
                final String headerFormat = "%5s  %6s  %7s  %-7s  %11s";
                return String.format(headerFormat, "level", "format", "threads", "test   ", "elapsedTime") + "\n" +
                       String.format(headerFormat, "-----", "------", "-------", "-------", "-----------");
            }

            @Override
            public String formatTestResult(final TestData result) {
                return String.format("%5d  %6s  %7d  %-7s  %11d",
                                     result.level, result.format, result.threads, result.test, result.getElapsedTime());
            }

            @Override
            public Map<String, Comparator<TestData>> getReportNameToComparatorMap() {
                final Map<String, Comparator<TestData>> map =
                        new LinkedHashMap<>();
                map.put("Level::Threads Results", levelThreadsComparator);
                return map;
            }

            private final Comparator<TestData> levelThreadsComparator =
                    new Comparator<TestData>() {
                        @Override
                        public int compare(final TestData o1,
                                           final TestData o2) {
                            int result = o1.level - o2.level;
                            if (result == 0) {
                                result = (int) (o1.getElapsedTime() - o2.getElapsedTime());
                                if (result == 0) {
                                    result = o1.threads - o2.threads;
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

        private final File renderParametersFile;
        private final String format;
        private final int level;
        private final int threads;
        private final String test;

        public TestData(final File renderParametersFile,
                        final String format,
                        final int level,
                        final int threads,
                        final String test) {
            if (! renderParametersFile.exists()) {
                throw new IllegalArgumentException(renderParametersFile.getAbsolutePath() + " not found");
            }
            this.renderParametersFile = renderParametersFile;
            this.format = format;
            this.level = level;
            this.threads = threads;
            this.test = test;
        }

        @Override
        public String getAverageGroup() {
            return level + "::" + format + "::" + threads;
        }

        @Override
        public String getReportGroup() {
            return String.valueOf(level);
        }

        public double getScale() {
            final double scale;
            if (level == 3) {
                scale = 0.1;
            } else if (level == 2) {
                scale = 0.17;
            } else {
                scale = 0.25;
            }
            return scale;
        }
    }
}
