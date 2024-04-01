package org.janelia.render.client.newsolver.errors;

import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.alignment.util.FileUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

public class AlignmentErrors {
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

	public Map<String, Double> accumulateForTiles() {
		final Map<String, Double> tileToError = new HashMap<>();
		pairwiseErrors.forEach(pairWithValue -> {
			final String pId = pairWithValue.getP().getId();
			final String qId = pairWithValue.getQ().getId();
			final double pErr = tileToError.computeIfAbsent(pId, k -> 0.0);
			tileToError.put(pId, (pErr + pairWithValue.getValue()));
			final double qErr = tileToError.computeIfAbsent(qId, k -> 0.0);
			tileToError.put(qId, (qErr + pairWithValue.getValue()));
		});
		return tileToError;
	}

	public static AlignmentErrors merge(final AlignmentErrors baseline, final AlignmentErrors other, final MergingMethod mergingMethod) {
		final AlignmentErrors differences = new AlignmentErrors();
		final Map<OrderedCanvasIdPair, Double> errorLookup = other.pairwiseErrors.stream()
				.collect(Collectors.toMap(OrderedCanvasIdPairWithValue::getPair, OrderedCanvasIdPairWithValue::getValue));

		baseline.pairwiseErrors.forEach(pairWithValue -> {
			final double otherError = errorLookup.get(pairWithValue.getPair());
			final double errorDifference = mergingMethod.function.applyAsDouble(pairWithValue.getValue(), otherError);
			differences.addError(pairWithValue.getPair(), errorDifference);
		});

		return differences;
	}

	public static void writeToFile(final AlignmentErrors errors, final String filename) throws IOException {
		FileUtil.saveJsonFile(filename, errors.pairwiseErrors);
	}

	public static AlignmentErrors loadFromFile(final String filename) throws IOException {
		final List<OrderedCanvasIdPairWithValue> loadedErrors = OrderedCanvasIdPairWithValue.loadList(filename);

		final AlignmentErrors errors = new AlignmentErrors();
		errors.pairwiseErrors.addAll(loadedErrors);
		return errors;
	}

	public enum MergingMethod {
		RELATIVE_CHANGE((a, b) -> b / a),
		ABSOLUTE_CHANGE((a, b) -> b - a),
		RELATIVE_DIFFERENCE((a, b) -> Math.abs(a - b) / a),
		ABSOLUTE_DIFFERENCE((a, b) -> Math.abs(a - b));

		private final DoubleBinaryOperator function;

		MergingMethod(final DoubleBinaryOperator function) {
			this.function = function;
		}
	}
}
