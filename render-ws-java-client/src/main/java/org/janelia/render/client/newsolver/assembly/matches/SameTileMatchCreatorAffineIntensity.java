package org.janelia.render.client.newsolver.assembly.matches;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mpicbg.models.Point;
import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.AffineModel1D;
import mpicbg.models.PointMatch;
import org.janelia.render.client.newsolver.BlockData;

public class SameTileMatchCreatorAffineIntensity implements SameTileMatchCreator<ArrayList<AffineModel1D>> {

	private BlockData<?, ?, ?, ?> blockContext = null;
	@Override
	public void addMatches(
			final TileSpec tileSpec,
			final ArrayList<AffineModel1D> modelsA,
			final ArrayList<AffineModel1D> modelsB,
			final List<PointMatch> matchesAtoB) {

		if (modelsA.size() != modelsB.size())
			throw new IllegalArgumentException("Lists of models for A and B must have the same size");

		final ArrayList<Double> averages = blockContext.idToAverages().get(tileSpec.getTileId());

		final int nModels = modelsA.size();
		for (int i = 0; i < nModels; ++i) {
			final AffineModel1D modelA = modelsA.get(i);
			final AffineModel1D modelB = modelsB.get(i);
			final double subTileAverage = averages.get(i);

			final double[] p = new double[] { subTileAverage };
			final double[] q = new double[] { subTileAverage };

			modelA.applyInPlace(p);
			modelB.applyInPlace(q);

			matchesAtoB.add(new PointMatch(new Point(p), new Point(q)));
		}
	}

	@Override
	public void setBlockContext(final BlockData<?, ?, ?, ?> blockContext) {
		this.blockContext = blockContext;
	}

}
