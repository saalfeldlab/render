package org.janelia.render.client.newsolver.solvers.affine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AffineBlockDataWrapper<M extends Model<M> & Affine2D<M>, S extends Model<S> & Affine2D<S>>
{
	final public static int samplesPerDimension = 2;

	final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData;

	//
	// local only variables needed for the block solve below
	//

	// all tiles, used for solving when not grouped
	final private HashMap<String, Tile< M > > idToTileMap = new HashMap<>();

	// used locally to map Tile back to TileId
	final private HashMap< Tile< M >, String > tileToIdMap = new HashMap<>();

	// stitching-related (local)

	// contains the model as after local stitching (tmp)
	final private HashMap<String, AffineModel2D> idToStitchingModel = new HashMap<>();

	// all grouped tiles, used for solving when stitching first
	final private HashMap< Tile< M >, Tile< M > > tileToGroupedTile = new HashMap<>();
	final private HashMap< Tile< M >, List< Tile< M > > > groupedTileToTiles = new HashMap<>();

	// removed layer-specific handling of restarts (needed for not using regularization with StabilizingAffineModel2D)
	// >> just do it for the entire block if needed
	// 
	// which z layers are restarts
	// final private HashSet< Integer > restarts = new HashSet<Integer>();

	// contains the model as loaded from renderer (can go right now except for debugging)
	final private HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();

	public AffineBlockDataWrapper( final BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>> blockData )
	{
		this.blockData = blockData;
	}

	public BlockData<AffineModel2D, FIBSEMAlignmentParameters<M, S>>  blockData() { return blockData; }

	public HashMap<String, Tile< M > > idToTileMap() { return idToTileMap; }
	public HashMap<Tile< M >, String > tileToIdMap() { return tileToIdMap; }
	public HashMap<String, AffineModel2D> idToStitchingModel() { return idToStitchingModel; }
	public HashMap< Tile< M >, Tile< M > > tileToGroupedTile() { return tileToGroupedTile; }
	public HashMap< Tile< M >, List< Tile< M > > > groupedTileToTiles() { return groupedTileToTiles; }
	//public HashSet< Integer > restarts() { return restarts; }
	public HashMap<String, AffineModel2D> idToPreviousModel() { return idToPreviousModel; }

	/**
	 * Removes data associated with any tile not identified in the provided set.
	 *
	 * @param  tileIdsToKeep  identifies which tile data should be retained.
	 */
	public void retainTiles(final Set<String> tileIdsToKeep) {

		final ResolvedTileSpecCollection rtsc = blockData.getResults().getResolvedTileSpecs();

		LOG.info("retainTiles: entry, idToTileMap.size={}, idToStitchingModel.size={}, idToPreviousModel.size={}, tileToIdMap.size={}, tileToGroupedTile.size={}, resultTileCount={}",
				 idToTileMap.size(),
				 idToStitchingModel.size(),
				 idToPreviousModel.size(),
				 tileToIdMap.size(),
				 tileToGroupedTile.size(),
				 rtsc.getTileCount());

		idToTileMap.entrySet().stream()
				.filter(e -> ! tileIdsToKeep.contains(e.getKey()))
				.forEach(e -> {
					final String tileId = e.getKey();
					idToTileMap.remove(tileId);
					idToStitchingModel.remove(tileId);
					idToPreviousModel.remove(tileId);
					final Tile<M> tile = e.getValue();
					tileToIdMap.remove(tile);
					tileToGroupedTile.remove(tile);
				});

		rtsc.retainTileSpecs(tileIdsToKeep);

		LOG.info("retainTiles: exit, idToTileMap.size={}, idToStitchingModel.size={}, idToPreviousModel.size={}, tileToIdMap.size={}, tileToGroupedTile.size={}, resultTileCount={}",
				 idToTileMap.size(),
				 idToStitchingModel.size(),
				 idToPreviousModel.size(),
				 tileToIdMap.size(),
				 tileToGroupedTile.size(),
				 rtsc.getTileCount());

		// groupedTileToTiles is only referenced from worker so no need to clean it up (I hope)
	}

	private static final Logger LOG = LoggerFactory.getLogger(AffineBlockDataWrapper.class);
}
