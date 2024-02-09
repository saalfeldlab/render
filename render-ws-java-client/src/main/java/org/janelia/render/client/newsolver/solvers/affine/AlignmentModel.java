package org.janelia.render.client.newsolver.solvers.affine;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AlignmentModel extends AbstractAffineModel2D<AlignmentModel> implements Model<AlignmentModel>, Affine2D<AlignmentModel> {

	private final List<AffineModel2DWrapper<?>> models = new ArrayList<>();
	private final List<Double> weights = new ArrayList<>();
	private final Map<String, Integer> nameToIndex = new HashMap<>();
	private final AffineModel2D affine = new AffineModel2D();

	public AlignmentModel() {}

	private AlignmentModel(
			final List<String> names,
			final List<AffineModel2DWrapper<?>> models,
			final List<Double> weights) {

		for (final String name : names)
			nameToIndex.put(name, nameToIndex.size());
		this.models.addAll(models);
		this.weights.addAll(weights);

		interpolate();
	}

	public int nModels() {
		return models.size();
	}

	public Model<?> getModel(final String name) {
		final int index = nameToIndex.get(name);
		return models.get(index).asModel();
	}

	public void setWeights(final Map<String, Double> nameToWeight) {
		if (!namesMatch(nameToWeight, nameToIndex))
			throw new IllegalArgumentException("Model names do not match");

		nameToWeight.forEach((name, weight) -> {
			final Integer index = nameToIndex.get(name);
			weights.set(index, weight);
		});

		interpolate();
	}

	private boolean namesMatch(final Map<String, ?> nameMap1, final Map<String, ?> nameMap2) {
		if (nameMap1.size() != nameMap2.size())
			return false;
		for (final String name : nameMap1.keySet())
			if (!nameMap2.containsKey(name))
				return false;
		return true;
	}


	public void interpolate() {
		// normalize weights
		final double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
		weights.replaceAll(aDouble -> aDouble / sum);

		// extract and interpolate coefficients
		final int nCoefficients = 6;
		final double[] c = new double[nCoefficients];
		final double[] cFinal = new double[nCoefficients];

		for (int k = 0; k < nModels(); ++k) {
			models.get(k).asAffine2D().toArray(c);
			final double w = weights.get(k);
			for (int i = 0; i < nCoefficients; ++i)
				cFinal[i] += w * c[i];
		}

		affine.set(cFinal[0], cFinal[1], cFinal[2], cFinal[3], cFinal[4], cFinal[5]);
	}

	@Override
	public AffineTransform createAffine() {
		return affine.createAffine();
	}

	public AffineModel2D createAffineModel2D() {
		return affine.copy();
	}

	@Override
	public AffineTransform createInverseAffine() {
		return affine.createInverseAffine();
	}

	@Override
	public void preConcatenate(final AlignmentModel other) {
		affine.preConcatenate(other.affine);
	}

	@Override
	public void concatenate(final AlignmentModel other) {
		affine.concatenate(other.affine);
	}

	public void preConcatenate(final AffineModel2D other) {
		affine.preConcatenate(other);
	}

	public void concatenate(final AffineModel2D other) {
		affine.concatenate(other);
	}

	@Override
	public void toArray(final double[] data) {
		affine.toArray(data);
	}

	@Override
	public void toMatrix(final double[][] data) {
		affine.toMatrix(data);
	}

	@Override
	public AlignmentModel createInverse() {
		throw new UnsupportedOperationException("Inverting an interpolated model does not make sense.");
	}

	@Override
	public double[] applyInverse(final double[] point) throws NoninvertibleModelException {
		final double[] copy = point.clone();
		applyInverseInPlace(copy);
		return copy;
	}

	@Override
	public void applyInverseInPlace(final double[] point) throws NoninvertibleModelException {
		affine.applyInverseInPlace(point);
	}

	@Override
	public int getMinNumMatches() {
		return models.stream().mapToInt(model -> model.asModel().getMinNumMatches()).max().orElse(0);
	}

	@Override
	public <P extends PointMatch> void fit(final Collection<P> matches) throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		for (final AffineModel2DWrapper<?> model : models)
			model.asModel().fit(matches);
		interpolate();
	}

	@Override
	public void set(final AlignmentModel other) {
		models.clear();
		models.addAll(other.models.stream().map(AffineModel2DWrapper::wrapCopy).collect(Collectors.toList()));

		weights.clear();
		weights.addAll(other.weights);

		nameToIndex.clear();
		nameToIndex.putAll(other.nameToIndex);

		affine.set(other.affine);
		cost = other.cost;
	}

	@Override
	public AlignmentModel copy() {
		final AlignmentModel copy = new AlignmentModel();
		copy.set(this);
		copy.cost = cost;
		return copy;
	}

	@Override
	public double[] apply(final double[] location) {
		final double[] copy = location.clone();
		applyInPlace(copy);
		return copy;
	}

	@Override
	public void applyInPlace(final double[] location) {
		affine.applyInPlace(location);
	}

	public static AlignmentModelBuilder configure() {
		return new AlignmentModelBuilder();
	}


	private static class AffineModel2DWrapper<T extends Model<T> & Affine2D<T>> implements Serializable {
		private final T wrappedInstance;

		public AffineModel2DWrapper(final T instance) {
			this.wrappedInstance = instance;
		}

		public Model<T> asModel() {
			return wrappedInstance;
		}

		public Affine2D<T> asAffine2D() {
			return wrappedInstance;
		}

		public static <S extends Model<S> & Affine2D<S>> AffineModel2DWrapper<S> wrapCopy(final AffineModel2DWrapper<S> wrapper) {
			return new AffineModel2DWrapper<S>(wrapper.asModel().copy());
		}
	}


	/**
	 * Builder for {@link AlignmentModel} that lets you add arbitrary many models by name.
	 */
	public static class AlignmentModelBuilder {
		private final Map<String, AffineModel2DWrapper<?>> nameToModel = new HashMap<>();

		/**
		 * Add a model to the list of models to be used in alignment.
		 *
		 * @param name name the model is referred to
		 * @param model model to be added
		 */
		public <T extends Model<T> & Affine2D<T>> AlignmentModelBuilder addModel(final String name, final T model) {
			final AffineModel2DWrapper<T> wrapper = new AffineModel2DWrapper<>(model);
			nameToModel.put(name, wrapper);
			return this;
		}

		/**
		 * Build the {@link AlignmentModel} from the added models. The models are initialized with equal weights.
		 *
		 * @return the {@link AlignmentModel}
		 * @throws IllegalStateException if no models were added
		 */
		public AlignmentModel build() {
			final int n = nameToModel.size();
			if (n == 0)
				throw new IllegalStateException("No models to build AlignmentModel from.");

			final List<String> names = new ArrayList<>(n);
			final List<AffineModel2DWrapper<?>> models = new ArrayList<>(n);
			final List<Double> weights = new ArrayList<>(n);

			nameToModel.forEach((name, model) -> {
				names.add(name);
				models.add(model);
				weights.add(1.0);
			});

			return new AlignmentModel(names, models, weights);
		}
	}
}
