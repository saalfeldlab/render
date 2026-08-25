package org.janelia.perf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for performance test data that supports averaging and sorting
 * result lists into a report.
 *
 * @author Eric Trautman
 */
public class PerformanceTestData {

    private long startTime;
    private long elapsedTime;

    public PerformanceTestData() {
        this.startTime = 0;
        this.elapsedTime = 0;
    }

    public void setStartTime() {
        startTime = System.currentTimeMillis();
    }

    public long getElapsedTime() {
        return elapsedTime;
    }

    public void setElapsedTime(final long elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    public void calculateElapsedTime() {
        elapsedTime = System.currentTimeMillis() - startTime;
    }

    public String getAverageGroup() {
        return null;
    }

    public String getReportGroup() {
        return "all";
    }

    /**
     * Utility for averaging, sorting, and printing performance test results.
     *
     * @param  <T>  the PerformanceTestData subclass used for testing.
     */
    public static abstract class TestResults<T extends PerformanceTestData> {

        public abstract T getAverageInstance(T groupInstance,
                                             long averageElapsedTime,
                                             int numberOfTests);

        public abstract String getReportHeader(String reportName);

        public abstract String formatTestResult(T result);

        public abstract Map<String, Comparator<T>> getReportNameToComparatorMap();

        public void collateAndPrintTimes(final List<T> results) {

            final List<T> finalResults = new ArrayList<>();

            final Map<String, List<T>> averageGroupToResultsMap = new LinkedHashMap<>();

            // -------------------------------------------
            // separate results into averaging groups

            List<T> averageGroupResults;
            for (final T result : results) {
                final String averageGroup = result.getAverageGroup();
                if (averageGroup == null) {
                    averageGroupResults = finalResults;
                } else {
                    averageGroupResults =
                            averageGroupToResultsMap.computeIfAbsent(averageGroup, k -> new ArrayList<>());
                }
                averageGroupResults.add(result);
            }

            // -------------------------------------------
            // calculate averages

            long sum;
            long averageTime;
            for (final String averageGroup : averageGroupToResultsMap.keySet()) {
                sum = 0;
                averageGroupResults = averageGroupToResultsMap.get(averageGroup);
                for (final PerformanceTestData result : averageGroupResults) {
                    sum += result.elapsedTime;
                }
                averageTime = sum / averageGroupResults.size();
                finalResults.add(getAverageInstance(averageGroupResults.getFirst(),
                                                    averageTime,
                                                    averageGroupResults.size()));
            }

            // -------------------------------------------
            // print sorted results

            if (!finalResults.isEmpty()) {

                final Map<String, Comparator<T>> reportNameToComparatorMap = getReportNameToComparatorMap();

                String reportGroup;
                for (final String reportName : reportNameToComparatorMap.keySet()) {

                    System.out.println(getReportHeader(reportName));

                    finalResults.sort(reportNameToComparatorMap.get(reportName));

                    reportGroup = finalResults.getFirst().getReportGroup();
                    for (final T result : finalResults) {
                        if (! reportGroup.equals(result.getReportGroup())) {
                            reportGroup = result.getReportGroup();
                            System.out.println();
                        }
                        System.out.println(formatTestResult(result));
                    }

                }

            }

        }

    }

}
