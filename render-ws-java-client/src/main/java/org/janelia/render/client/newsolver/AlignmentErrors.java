package org.janelia.render.client.newsolver;

import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.alignment.util.FileUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

class AlignmentErrors {
	private final List<OrderedCanvasIdPairWithValue> pairwiseErrors = new ArrayList<>();

	public void addError(final OrderedCanvasIdPair pair, final double error) {
		pairwiseErrors.add(new OrderedCanvasIdPairWithValue(pair, error));
	}

	public void absorb(final AlignmentErrors other) {
		pairwiseErrors.addAll(other.pairwiseErrors);
	}

	public List<OrderedCanvasIdPairWithValue> getWorstPairs(final int n) {
		return pairwiseErrors.stream()
				.sorted((p1, p2) -> Double.compare(p2.getValue(), p1.getValue()))
				.limit(n)
				.collect(Collectors.toList());
	}

	public static AlignmentErrors computeDifferences(final AlignmentErrors baseline, final AlignmentErrors other, final DoubleBinaryOperator comparisonMetric) {
		final AlignmentErrors differences = new AlignmentErrors();
		final Map<OrderedCanvasIdPair, Double> errorLookup = other.pairwiseErrors.stream()
				.collect(Collectors.toMap(OrderedCanvasIdPairWithValue::getPair, OrderedCanvasIdPairWithValue::getValue));

		baseline.pairwiseErrors.forEach(pairWithValue -> {
			final double otherError = errorLookup.get(pairWithValue.getPair());
			final double errorDifference = comparisonMetric.applyAsDouble(pairWithValue.getValue(), otherError);
			differences.addError(pairWithValue.getPair(), errorDifference);
		});

		return differences;
	}

	public static void writeToFile(final AlignmentErrors errors, final String filename) throws IOException {
		FileUtil.saveJsonFile(filename, errors.pairwiseErrors);
	}
}
