package org.janelia.render.client.intensityadjust;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel1D;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.QuadraticOnTheFlyIntensity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QuadraticIntensityCorrectionStrategy
		implements IntensityCorrectionStrategy {

	private final InterpolatedQuadraticAffineModel1D<?,?> quadraticModelTemplate;
	private final InterpolatedQuadraticAffineModel1D<?,?> affineModelTemplate;
	private final Set<Double> quadraticZValues;

	public QuadraticIntensityCorrectionStrategy(final double lambda1,
			final double lambda2,
			final Double firstLayerZ) {
		this(lambda1, lambda2, new HashSet<>(Collections.singletonList(firstLayerZ)));
	}

	public QuadraticIntensityCorrectionStrategy(final double lambda1,
			final double lambda2,
			final Set<Double> quadraticZValues) {

		this.quadraticZValues = quadraticZValues;

		quadraticModelTemplate = new InterpolatedQuadraticAffineModel1D<>(
				new InterpolatedQuadraticAffineModel1D<>(
						new QuadraticModel1D(), // purely quadratic ...
						new TranslationModel1D(), lambda1), // ... with a bit of translation ...
				new IdentityModel(), lambda2); // ... and a bit of identity

		affineModelTemplate = new InterpolatedQuadraticAffineModel1D<>(
				new InterpolatedQuadraticAffineModel1D<>(
						new InterpolatedQuadraticAffineModel1D<>(
								new QuadraticModel1D(), new AffineModel1D(), 1.0), // purely affine ...
						new TranslationModel1D(), lambda1), // ... with a bit of translation ...
				new IdentityModel(), lambda2); // ... and a bit of identity
	}

	@Override
	@SuppressWarnings("unchecked") // modelTemplate is always of the type given above
	public <M extends Model<M>> M getModelFor(final TileSpec p) {
		if (quadraticZValues.contains(p.getZ())) {
			return (M) quadraticModelTemplate.copy();
		} else {
			return (M) affineModelTemplate.copy();
		}
	}

	@Override
	// TODO: this code should be computed on-the-fly as a function of the coefficients
	// TODO: replace interface Affine1D once a better alternative is available
	public ArrayList<OnTheFlyIntensity> getOnTheFlyIntensities(
			final List<TileSpec> patches,
			final int numCoefficients,
			final Map<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles) {

		final ArrayList<OnTheFlyIntensity> correctedOnTheFly = new ArrayList<>();

		for (final TileSpec p : patches) {
			/* save coefficients */
			final double[][] abc_coefficients = new double[numCoefficients * numCoefficients][3];

			final ArrayList<Tile<? extends Affine1D<?>>> tiles = coefficientsTiles.get(p);

			for (int i = 0; i < numCoefficients * numCoefficients; ++i) {
				final Affine1D<?> model = tiles.get(i).getModel();
				model.toArray(abc_coefficients[i]);
			}

			correctedOnTheFly.add(new QuadraticOnTheFlyIntensity(p, abc_coefficients, numCoefficients));
		}

		return correctedOnTheFly;
	}

	@Override
	public PointMatchFilter provideOutlierRemoval() {
		return new RansacRegressionReduceFilter(new QuadraticModel1D());
	}
}
