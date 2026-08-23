package org.janelia.render.client.emshading;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import org.janelia.alignment.filter.emshading.QuadraticShading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QuadraticBackgroundTest {

	@Test
	public void simpleModelProducesCorrectResults() throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		// 0.5 * x^2 + 0.5 * y^2 + 1
		final QuadraticShading background = new QuadraticShading(new double[]{1, 0, 0, 0.5, 0, 0.5});

		final double[] location1 = new double[]{0, 0};
		background.applyInPlace(location1);
		assertEquals(1, location1[0], 1e-12);

		final double[] location2 = new double[]{1, 1};
		background.applyInPlace(location2);
		assertEquals(2, location2[0], 1e-12);

		final double[] location3 = new double[]{-1, 1};
		background.applyInPlace(location3);
		assertEquals(2, location3[0], 1e-12);

		final double[] location4 = new double[]{1, 0};
		background.applyInPlace(location4);
		assertEquals(1.5, location4[0], 1e-12);

		final double[] location5 = new double[]{0, 1};
		background.applyInPlace(location5);
		assertEquals(1.5, location5[0], 1e-12);
	}

	@Test
	public void simpleModelIsComputedCorrectly() throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final List<PointMatch> matches = new ArrayList<>();

		// minimal set of points to fit 0.5 * x^2 + 0.5 * y^2
		matches.add(new PointMatch(new Point(new double[]{0, 0}), new Point(new double[]{0})));
		matches.add(new PointMatch(new Point(new double[]{1, 1}), new Point(new double[]{1})));
		matches.add(new PointMatch(new Point(new double[]{-1, 1}), new Point(new double[]{1})));
		matches.add(new PointMatch(new Point(new double[]{1, -1}), new Point(new double[]{1})));
		matches.add(new PointMatch(new Point(new double[]{-1, -1}), new Point(new double[]{1})));
		matches.add(new PointMatch(new Point(new double[]{0, 1}), new Point(new double[]{0.5})));

		final QuadraticShading background = new QuadraticShading();
		background.fit(matches);

		// order of coefficients: {1, y, x, y^2, x*y, x^2}
		assertArrayEquals(new double[]{0, 0, 0, 0.5, 0, 0.5}, background.getCoefficients(), 1e-12);
	}

	@Test
	public void modelCanBeRecoveredWithManyPoints() throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final QuadraticShading background = new QuadraticShading(new double[]{1, 2, 3, 4, 5, 6});

		final List<PointMatch> matches = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final double x = Math.random() * 2 - 1;
			final double y = Math.random() * 2 - 1;
			final double[] location = new double[]{x, y};
			background.applyInPlace(location);
			matches.add(new PointMatch(new Point(new double[]{x, y}), new Point(new double[]{location[0]})));
		}

		final QuadraticShading recovered = new QuadraticShading();
		recovered.fit(matches);

		assertArrayEquals(background.getCoefficients(), recovered.getCoefficients(), 1e-12);
	}
}
