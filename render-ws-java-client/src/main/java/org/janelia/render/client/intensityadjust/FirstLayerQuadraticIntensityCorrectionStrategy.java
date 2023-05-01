package org.janelia.render.client.intensityadjust;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel1D;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.QuadraticOnTheFlyIntensity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FirstLayerQuadraticIntensityCorrectionStrategy implements IntensityCorrectionStrategy {

	static InterpolatedQuadraticAffineModel1D<?,?> firstLayerModelTemplate;
	static InterpolatedQuadraticAffineModel1D<?,?> genericModelTemplate;
	private final Double firstLayerZ;

	public FirstLayerQuadraticIntensityCorrectionStrategy(final double lambda, final Double firstLayerZ) {
		this.firstLayerZ = firstLayerZ;
		firstLayerModelTemplate = new InterpolatedQuadraticAffineModel1D<>(
						new QuadraticModel1D(), new IdentityModel(), lambda); // mainly qudratic, regularized with identity
		genericModelTemplate = new InterpolatedQuadraticAffineModel1D<>(
				new InterpolatedQuadraticAffineModel1D<>(
						new InterpolatedQuadraticAffineModel1D<>(
								new QuadraticModel1D(), new AffineModel1D(), 1.0), // purely affine ...
						new TranslationModel1D(), lambda), // ... with a bit of translation ...
				new IdentityModel(), lambda); // ... and a bit of identity
	}

	@Override
	@SuppressWarnings("unchecked") // modelTemplate is always of the type given above
	public <M extends Model<M>> M getModelFor(MinimalTileSpecWrapper p) {
		if (p.getZ() == firstLayerZ)
			return (M) firstLayerModelTemplate.copy();
		else
			return (M) genericModelTemplate.copy();
	}

	@Override
	// TODO: this code should be computed on-the-fly as a function of the coefficients
	// TODO: replace interface Affine1D once a better alternative is available
	public ArrayList<OnTheFlyIntensity> getOnTheFlyIntensities(
			final List<MinimalTileSpecWrapper> patches,
			final int numCoefficients,
			final Map<MinimalTileSpecWrapper, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles) {

		final ArrayList<OnTheFlyIntensity> correctedOnTheFly = new ArrayList<>();

		for (final MinimalTileSpecWrapper p : patches) {
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
