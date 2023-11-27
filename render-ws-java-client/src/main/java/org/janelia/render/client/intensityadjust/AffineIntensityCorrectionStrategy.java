package org.janelia.render.client.intensityadjust;

import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel1D;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;
import org.janelia.render.client.intensityadjust.virtual.LinearOnTheFlyIntensity;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AffineIntensityCorrectionStrategy implements IntensityCorrectionStrategy {

	public static double DEFAULT_LAMBDA = 0.01;

	private final InterpolatedAffineModel1D<InterpolatedAffineModel1D<AffineModel1D, TranslationModel1D>, IdentityModel> modelTemplate;

	public AffineIntensityCorrectionStrategy() {
		this(DEFAULT_LAMBDA, DEFAULT_LAMBDA);
	}

	public AffineIntensityCorrectionStrategy(final double lambda1, final double lambda2) {
		 modelTemplate = new InterpolatedAffineModel1D<>(
				new InterpolatedAffineModel1D<>(
						new AffineModel1D(), new TranslationModel1D(), lambda1),
				new IdentityModel(), lambda2);
	}

	@Override
	// TODO: this code should be computed on-the-fly as a function of the coefficients
	public ArrayList<OnTheFlyIntensity> getOnTheFlyIntensities(
			final List<TileSpec> patches,
			final int numCoefficients,
			final Map<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles) {

		final ArrayList<OnTheFlyIntensity> correctedOnTheFly = new ArrayList<>();
		for (final TileSpec p : patches) {
			/* save coefficients */
			final double[][] ab_coefficients = new double[numCoefficients * numCoefficients][2];

			final ArrayList<Tile<? extends Affine1D<?>>> tiles = coefficientsTiles.get(p);

			for (int i = 0; i < numCoefficients * numCoefficients; ++i)
			{
				final Tile<? extends Affine1D<?>> t = tiles.get(i);
				final Affine1D<?> affine = t.getModel();
				affine.toArray(ab_coefficients[i]);
			}

			correctedOnTheFly.add(new LinearOnTheFlyIntensity(p, ab_coefficients, numCoefficients ));
		}
		return correctedOnTheFly;
	}

	@Override
	@SuppressWarnings("unchecked") // modelTemplate is always of the type given above
	public <M extends Model<M>> M getModelFor(final TileSpec p) {
		return (M) modelTemplate.copy();
	}

	@Override
	public PointMatchFilter provideOutlierRemoval() {
		return new RansacRegressionReduceFilter(new AffineModel1D());
	}
}
