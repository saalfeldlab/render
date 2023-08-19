package org.janelia.render.client.newsolver.assembly.matches;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;

import mpicbg.models.AffineModel1D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

public class SameTileMatchCreatorAffineIntensity< F extends BlockFactory< F > > implements SameTileMatchCreator<ArrayList<AffineModel1D>, F> {

	@Override
	public void addMatches(
			final TileSpec tileSpec,
			final ArrayList<AffineModel1D> modelsA,
			final ArrayList<AffineModel1D> modelsB,
			final BlockData<?, ArrayList<AffineModel1D>, ?, F> blockContextA,
			final BlockData<?, ArrayList<AffineModel1D>, ?, F> blockContextB,
			final List<PointMatch> matchesAtoB) {

		if (modelsA.size() != modelsB.size())
			throw new IllegalArgumentException("Lists of models for A and B must have the same size");

		// TODO: in this case it doesn't matter if it's A or B, for other cases the blocks might be useful
		final ArrayList<Double> averages = blockContextA.idToAverages().get(tileSpec.getTileId());

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
}
