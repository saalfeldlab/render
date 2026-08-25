package org.janelia.render.client.solver.matchfilter;

import java.util.List;

import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.PointMatch;

public interface MatchFilter
{
	List<PointMatch> filter(final Matches matches,
                            final TileSpec pTileSpec,
                            final TileSpec qTileSpec);
}
