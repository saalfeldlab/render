package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.AlignmentErrors.MergingMethod;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class StackAlignmentComparisonClient {

	public static class Parameters extends CommandLineParameters {
		@Parameter(names = "--baselineFile", description = "Name of file from which to read pairwise errors as baseline", required = true)
		private String baselineFile;
		@Parameter(names = "--otherFile", description = "Name of file from which to read other pairwise errors", required = true)
		private String otherFile;
		@Parameter(names = "--metric", description = "Metric to use for comparing errors (default: RELATIVE)")
		private MergingMethod metric = MergingMethod.RELATIVE_DIFFERENCE;
		@Parameter(names = "--outFileName", description = "Name of file to write pairwise error differences to (not written if not specified)")
		private String outFileName = null;
		@Parameter(names = "--reportWorstPairs", description = "Report the worst n pairs (default: 50)")
		private int reportWorstPairs = 50;
	}


	private final Parameters params;

	public StackAlignmentComparisonClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) throws Exception {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final StackAlignmentComparisonClient client = new StackAlignmentComparisonClient(parameters);
				client.compareErrors();
			}
		};
		clientRunner.run();
	}

	public void compareErrors() throws IOException {

		final AlignmentErrors baseline = AlignmentErrors.loadFromFile(params.baselineFile);
		final AlignmentErrors other = AlignmentErrors.loadFromFile(params.otherFile);

		final AlignmentErrors differences = AlignmentErrors.merge(baseline, other, params.metric);

		final List<OrderedCanvasIdPairWithValue> worstPairs = differences.getWorstPairs(params.reportWorstPairs);
		System.out.println("Worst pairs:");
		for (final OrderedCanvasIdPairWithValue pairWithError : worstPairs) {
			System.out.println(pairWithError.getP() + " - " + pairWithError.getQ() + " : " + pairWithError.getValue());
		}

		if (params.outFileName != null) {
			AlignmentErrors.writeToFile(differences, params.outFileName);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
