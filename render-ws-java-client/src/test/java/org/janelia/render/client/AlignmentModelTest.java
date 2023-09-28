package org.janelia.render.client;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import org.janelia.render.client.newsolver.solvers.affine.AlignmentModel;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.janelia.render.client.newsolver.solvers.affine.AlignmentModel.AlignmentModelBuilder;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AlignmentModelTest {

	private static final int N_COEFFICIENTS = 6;
	private static final AffineModel2D affine = new AffineModel2D();
	private static final RigidModel2D rigid = new RigidModel2D();
	private static final TranslationModel2D translation = new TranslationModel2D();
	private static final StabilizingAffineModel2D<RigidModel2D> stabilizing = new StabilizingAffineModel2D<>(rigid);
	private static final double lAffine = 1.0;
	private static final double lRigid = 0.1;
	private static final double lTrans = 0.2;
	private static final double lStab = 0.3;
	private static final double delta = 1e-12;

	@BeforeClass
	public static void setDefaultModels() {
		affine.set(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
		rigid.set(1.0, 1.0, 2.0);
		translation.set(0.1, 0.2);
	}

	@Test
	public void affineModelIsInterpolatedToAffine() {
		final double[] expected = arrayFromModel(affine);
		final AlignmentModel model = AlignmentModel.configure().addModel("affine", affine).build();

		model.interpolate();
		final double[] actual = arrayFromModel(model);

		assertArrayEquals(expected, actual, delta);
	}

	@Test
	public void interpolatingTwoModelsIsCorrect() {
		final InterpolatedAffineModel2D<AffineModel2D, RigidModel2D> interpolated = new InterpolatedAffineModel2D<>(affine, rigid, lRigid);

		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("rigid", rigid).build();
		model.setWeights(Map.of("affine", 1 - lRigid, "rigid", lRigid));

		final double[] expected = arrayFromModel(interpolated);
		final double[] actual = arrayFromModel(model);

		assertArrayEquals(expected, actual, delta);
	}

	@Test
	public void nestedInterpolationGivesSameResults() {
		final InterpolatedAffineModel2D<?, ?> interpolated = getNestedInterpolatedModel();
		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("rigid", rigid)
				.addModel("translation", translation)
				.addModel("stabilizing", stabilizing).build();
		final Map<String, Double> weights = computeNestedWeights();
		model.setWeights(weights);

		final double[] expected = arrayFromModel(interpolated);
		final double[] actual = arrayFromModel(model);

		assertArrayEquals(expected, actual, delta);
	}

	@Test
	public void interpolatingTheSameModelYieldsModel() {
		final AlignmentModelBuilder builder = new AlignmentModelBuilder();
		final Map<String, Double> weights = new HashMap<>();
		for (int i = 0; i < 7; i++) {
			final String name = "affine" + i;
			builder.addModel(name, rigid);
			weights.put(name, (double) i);
		}
		final AlignmentModel model = builder.build();
		model.setWeights(weights);

		final double[] expected = arrayFromModel(rigid);
		final double[] actual = arrayFromModel(model);

		assertArrayEquals(expected, actual, delta);
	}

	@Test
	public void fittingYieldsRightResult() throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		final InterpolatedAffineModel2D<AffineModel2D, TranslationModel2D> interpolated = new InterpolatedAffineModel2D<>(affine.copy(), translation.copy(), 0.5);
		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("translation", translation).build();
		model.setWeights(Map.of("affine", 1.0, "translation", 1.0));

		final List<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch(new Point(new double[] { 0, 0 }), new Point(new double[] { 0, 0.1 })));
		matches.add(new PointMatch(new Point(new double[] { 0, 1 }), new Point(new double[] { 0.1, 1 })));
		matches.add(new PointMatch(new Point(new double[] { 1, 0 }), new Point(new double[] { 1, 0 })));
		matches.add(new PointMatch(new Point(new double[] { 1, 1 }), new Point(new double[] { 1, 1.1 })));
		matches.add(new PointMatch(new Point(new double[] { 0.5, 0.5 }), new Point(new double[] { 0.5, 0.5 })));

		interpolated.fit(matches);
		model.fit(matches);

		final double[] expected = arrayFromModel(interpolated);
		final double[] actual = arrayFromModel(model);

		assertArrayEquals(expected, actual, delta);
	}

	@Test
	public void applyingInverseReversesTransformation() {
		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("stabilizing", stabilizing).build();
		model.setWeights(Map.of(
				"affine", lAffine,
				"stabilizing", lStab));
		final double[] expected = new double[] { Math.PI, Math.E };

		final double[] transformedPoint = model.apply(expected);
		assertTrue(distanceBetween(expected, transformedPoint) > delta);

		try {
			final double[] actual = model.applyInverse(transformedPoint);
			assertArrayEquals(expected, actual, delta);
		} catch (final NoninvertibleModelException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void copyingWorksAsExpected() {
		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("translation", translation).build();
		model.setWeights(Map.of(
				"affine", lAffine,
				"translation", lTrans));
		final AlignmentModel copy = model.copy();

		final double[] expected = arrayFromModel(model);
		final double[] actual = arrayFromModel(copy);
		assertArrayEquals(expected, actual, delta);

		model.setWeights(Map.of(
				"affine", lAffine,
				"translation", 2*lTrans));

		final double[] changed = arrayFromModel(model);
		assertTrue(distanceBetween(expected, changed) > delta);
	}

	@Test
	public void settingWeightsWithoutMatchingNamesThrowsError() {
		final AlignmentModel model = AlignmentModel.configure()
				.addModel("affine", affine)
				.addModel("rigid", rigid).build();
		try {
			model.setWeights(Map.of("affine", lAffine));
			fail("Expected IllegalArgumentException");
		} catch (final IllegalArgumentException e) {
			// pass
		}
	}

	private double distanceBetween(final double[] pointA, final double[] pointB) {
		double distSquared = 0;
		for (int i = 0; i < pointA.length; i++) {
			final double diff = pointA[i] - pointB[i];
			distSquared += diff * diff;
		}
		return Math.sqrt(distSquared);
	}

	private static double[] arrayFromModel(final Affine2D<?> affine2D) {
		final double[] data = new double[N_COEFFICIENTS];
		affine2D.toArray(data);
		return data;
	}

	private static Map<String, Double> computeNestedWeights() {
		return Map.of(
				"affine", (1-lRigid) * (1-lTrans) * (1-lStab),
				"rigid", lRigid * (1-lTrans) * (1-lStab),
				"translation", lTrans * (1-lStab),
				"stabilizing", lStab);
	}

	private static InterpolatedAffineModel2D<InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, TranslationModel2D>, StabilizingAffineModel2D<RigidModel2D>>
			getNestedInterpolatedModel() {
		return new InterpolatedAffineModel2D<>(
				new InterpolatedAffineModel2D<>(
						new InterpolatedAffineModel2D<>(
								affine,
								rigid, lRigid),
						translation, lTrans),
				stabilizing, lStab);
	}
}
