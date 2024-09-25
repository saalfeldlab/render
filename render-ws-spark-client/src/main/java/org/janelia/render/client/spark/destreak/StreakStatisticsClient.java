package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class StreakStatisticsClient implements Serializable {

		public static class Parameters extends CommandLineParameters {

			@ParametersDelegate
			public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

			@Parameter(names = "--stack", description = "Stack to pull image and transformation data from", required = true)
			public String stack;

			@Parameter(names = "--threshold", description = "Threshold used to convert the streak mask to a binary mask", required = true)
			public double threshold;

			@Parameter(names = "--blurRadius", description = "Radius of the Gaussian blur applied to the streak mask", required = true)
			private int blurRadius;

			@Parameter(names = "--meanFilterSize", description = "Number of pixels to average in the y-direction (must be odd)", required = true)
			private int meanFilterSize;

			@Parameter(names = "--output", description = "Output file path", required = true)
			public String outputPath;

			@Parameter(names = "--nCells", description = "Number of cells to use in x and y directions, e.g., 5x3", required = true)
			public String cells;

			private int nX = -1;
			private int nY = -1;

			public void validate() {
				if (threshold < 1) {
					throw new IllegalArgumentException("threshold must be positive");
				}
				if (blurRadius < 1) {
					throw new IllegalArgumentException("blurRadius must be positive");
				}
				if (meanFilterSize < 1 || meanFilterSize % 2 == 0) {
					throw new IllegalArgumentException("meanFilterSize must be positive and odd");
				}
				try {
					final String[] nCells = cells.split("x");
					nX = Integer.parseInt(nCells[0]);
					nY = Integer.parseInt(nCells[1]);
					if (nX < 1 || nY < 1) {
						throw new IllegalArgumentException("nCells must be positive");
					}
				} catch (final NumberFormatException e) {
					throw new IllegalArgumentException("nCells must be in the format 'NxM'");
				}
			}

			public int nCellsX() {
				if (nX == -1) {
					validate();
				}
				return nX;
			}

			public int nCellsY() {
				if (nY == -1) {
					validate();
				}
				return nY;
			}
		}

		public static void main(final String[] args) {
			final ClientRunner clientRunner = new ClientRunner(args) {
				@Override
				public void runClient(final String[] args) throws Exception {

					final Parameters parameters = new Parameters();
					parameters.parse(args);
					LOG.info("runClient: entry, parameters={}", parameters);
					parameters.validate();

					final StreakStatisticsClient client = new StreakStatisticsClient(parameters);
					client.run();
				}
			};
			clientRunner.run();
		}


	private static final Logger LOG = LoggerFactory.getLogger(StreakStatisticsClient.class);
	private final Parameters parameters;

	public StreakStatisticsClient(final Parameters parameters) {
		this.parameters = parameters;
	}

	public void run() {
	}
}
