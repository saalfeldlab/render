package org.janelia.render.client.intensityadjust;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface IntensityCorrectionStrategy extends Serializable {

	<T extends Model<T>> T getModelFor(TileSpec p);

	ArrayList<OnTheFlyIntensity> getOnTheFlyIntensities(
			final List<TileSpec> patches,
			final int numCoefficients,
			final Map<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles);

	PointMatchFilter provideOutlierRemoval();
}
