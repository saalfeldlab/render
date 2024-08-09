package org.janelia.render.client;

public class StreakStatisticsTest {
	public static void main(final String[] args) {
		final String[] testArgs = {
				"--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
				"--owner", "cellmap",
				"--project", "jrc_atla52_b10_2",
				"--stack", "v1_acquire_align",
				"--zValues", "10,100,1000",
				"--context", "3",
				"--outputFileFormat", "streak_statistics_z%05d.csv",
//				"--maskStorageLocation", "masks",
				"--meanFilterSize", "201",
				"--threshold", "10.0",
				"--blurRadius", "3"
		};
		StreakStatisticsClient.main(testArgs);
	}
}
