package org.janelia.render.client.newsolver.solvers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.affine.AffineBlockDataWrapper;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.SolveTools;

import com.esotericsoftware.minlog.Log;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;

public class WorkerTools
{
	public static int fixIds( final List<? extends BlockData<?, ?, ?, ?>> allItems, final int maxId )
	{
		final HashSet<Integer> existingIds = new HashSet<>();

		for ( final BlockData<?, ?, ?, ?> item : allItems )
		{
			final int id = item.getId();

			if ( existingIds.contains( id ) )
			{
				// duplicate id
				if ( id <= maxId )
					throw new RuntimeException( "Id: " + id + " exists, but is <= maxId=" + maxId + ", this should never happen." );

				final int newId = Math.max( maxId, max( existingIds ) ) + 1;
				item.assignUpdatedId( newId );
				existingIds.add( newId );

				Log.info( "Assigning new id " + newId + " to block " + id);
			}
			else
			{
				Log.info( "Keeping id " + id);
				existingIds.add( id );
			}
		}

		return max( existingIds );
	}

	protected static int max( final Collection< Integer > ids )
	{
		int max = Integer.MIN_VALUE;

		for ( final int i : ids )
			max = Math.max( i, max );

		return max;
	}

	public static class LayerDetails< M extends Model<M> >
	{
		final public String tileId;
		final public int tileCol, tileRow;
		final public Tile< M > prevGroupedTile;

		public LayerDetails( final String tileId, final int tileCol, final int tileRow, final Tile< M > prevGroupedTile )
		{
			this.tileId = tileId;
			this.tileCol = tileCol;
			this.tileRow = tileRow;
			this.prevGroupedTile = prevGroupedTile;
		}
	}

	public static < M extends Model<M> & Affine2D< M >> ArrayList< LayerDetails< M > > layerDetails(
			final ArrayList< Integer > allZ,
			final HashMap< Integer, List<Tile<M>> > zToGroupedTileList,
			final AffineBlockDataWrapper< M, ?, ? > solveItem,
			final int i )
	{
		final ArrayList< LayerDetails< M > > prevTiles = new ArrayList<>();

		if ( i < 0 || i >= allZ.size() )
			return prevTiles;

		for ( final Tile< M > prevGroupedTile : zToGroupedTileList.get( allZ.get( i ) ) )
			for ( final Tile< M > imageTile : solveItem.groupedTileToTiles().get( prevGroupedTile ) )
			{
				final String tileId = solveItem.tileToIdMap().get( imageTile );
				final int tileCol = solveItem.blockData().rtsc().getTileSpec( tileId ).getLayout().getImageCol();//.getImageCol();
				final int tileRow = solveItem.blockData().rtsc().getTileSpec( tileId ).getLayout().getImageRow();//

				prevTiles.add( new LayerDetails<>(tileId, tileCol, tileRow, prevGroupedTile ) );//new ValuePair<>( new ValuePair<>( tileCol, tileId ), prevGroupedTile ) );
			}

		return prevTiles;
	}

	public static double computeAlignmentError(
			final Model< ? > crossLayerModel,
			final Model< ? > montageLayerModel,
			final TileSpec pTileSpec,
			final TileSpec qTileSpec,
			final Model< ? > pAlignmentModel, // solveItem.idToNewModel().get( pTileId ), // p
			final Model< ? > qAlignmentModel, // solveItem.idToNewModel().get( qTileId ) ); // q
			final Matches matches )
	{
		return SolveTools.computeAlignmentError(
				crossLayerModel,
				montageLayerModel,
				new MinimalTileSpec( pTileSpec ),
				new MinimalTileSpec( qTileSpec ),
				pAlignmentModel,
				qAlignmentModel,
				matches,
				10);
	}

}
