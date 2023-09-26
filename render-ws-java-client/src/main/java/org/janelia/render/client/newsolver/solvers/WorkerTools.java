package org.janelia.render.client.newsolver.solvers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.solvers.affine.AffineBlockDataWrapper;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.SolveTools;

import com.esotericsoftware.minlog.Log;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerTools
{
	public static AffineModel2D createAffine( final Affine2D< ? > model )
	{
		final AffineModel2D m = new AffineModel2D();
		m.set( model.createAffine() );

		return m;
	}

	public static int fixIds( final List<? extends BlockData<?, ?>> allItems, final int maxId )
	{
		final HashSet<Integer> existingIds = new HashSet<>();

		for ( final BlockData<?, ?> item : allItems )
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
		return ids.stream().max(Integer::compare).orElse(Integer.MIN_VALUE);
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
			final AffineBlockDataWrapper<M, ?> solveItem,
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
			final CoordinateTransform pAlignmentTransform,
			final CoordinateTransform qAlignmentTransform,
			final Matches matches )
	{
		return SolveTools.computeAlignmentError(
				crossLayerModel,
				montageLayerModel,
				new MinimalTileSpec( pTileSpec ),
				new MinimalTileSpec( qTileSpec ),
				pAlignmentTransform,
				qAlignmentTransform,
				matches,
				10);
	}

	/**
	 * Adaptation of {@link Tile#traceConnectedGraph} that avoids StackOverflowError from
	 * too much recursion when dealing with larger connected graphs.
	 */
	@SuppressWarnings("JavadocReference")
	private static void safelyTraceConnectedGraph(final Tile<?> forTile,
			final Set<Tile<?>> graph,
			final Set<Tile<?>> deferredTiles,
			final int recursionDepth) {
		final int maxRecursionDepth = 500;

		graph.add(forTile);

		for (final Tile<?> t : forTile.getConnectedTiles()) {
			if (! (graph.contains(t) || deferredTiles.contains(t))) {
				if (recursionDepth < maxRecursionDepth) {
					safelyTraceConnectedGraph(t, graph, deferredTiles, recursionDepth + 1);
				} else {
					deferredTiles.add(t);
				}
			}
		}
	}

	/**
	 * Adaptation of {@link Tile#identifyConnectedGraphs} that avoids StackOverflowError from
	 * too much recursion when dealing with larger connected graphs.
	 */
	public static ArrayList<Set<Tile<?>>> safelyIdentifyConnectedGraphs(final Collection<Tile<?>> tiles) {

		LOG.info("safelyIdentifyConnectedGraphs: entry, checking {} tiles", tiles.size());

		final ArrayList<Set<Tile<?>>> graphs = new ArrayList<>();
		int numInspectedTiles = 0;
		A:		for (final Tile<?> tile : tiles)
		{
			for (final Set<Tile<?>> knownGraph : graphs) {
				if (knownGraph.contains(tile)) {
					continue A;
				}
			}

			final Set<Tile<?>> currentGraph = new HashSet<>();
			final Set< Tile< ? > > deferredTiles = new HashSet<>();
			safelyTraceConnectedGraph(tile, currentGraph, deferredTiles, 0);

			while (!deferredTiles.isEmpty()) {
				LOG.info("safelyIdentifyConnectedGraphs: {} max recursion deferred tiles, current graph size is {}",
						 deferredTiles.size(), currentGraph.size());
				final List<Tile<?>> toDoList = new ArrayList<>(deferredTiles);
				deferredTiles.clear();
				for (final Tile<?> toDoTile : toDoList) {
					safelyTraceConnectedGraph(toDoTile, currentGraph, deferredTiles, 0);
				}
			}

			numInspectedTiles += currentGraph.size();
			graphs.add(currentGraph);

			if (numInspectedTiles == tiles.size()) {
				break;
			}
		}

		LOG.info("safelyIdentifyConnectedGraphs: returning {} graph(s)", graphs.size());

		return graphs;
	}

	private static final Logger LOG = LoggerFactory.getLogger(WorkerTools.class);
}
