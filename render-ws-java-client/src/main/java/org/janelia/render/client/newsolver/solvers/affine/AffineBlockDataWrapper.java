package org.janelia.render.client.newsolver.solvers.affine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.janelia.alignment.match.Matches;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import net.imglib2.util.Pair;

public class AffineBlockDataWrapper< M extends Model< M > & Affine2D< M >, S extends Model< S > & Affine2D< S >, P extends FIBSEMAlignmentParameters< M, S >, F extends BlockFactory< F > >
{
	final public static int samplesPerDimension = 2;

	final BlockData< M, P, F > blockData;

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

	// which z layers are restarts
	final private HashSet< Integer > restarts = new HashSet<Integer>();

	// matches for error computation
	final List< Pair< Pair< String, String>, Matches > > matches = new ArrayList<>();

	public AffineBlockDataWrapper( final BlockData< M, P, F > blockData )
	{
		this.blockData = blockData;
	}

	public BlockData< M, P, F >  blockData() { return blockData; }

	public HashMap<String, Tile< M > > idToTileMap() { return idToTileMap; }
	public List< Pair< Pair< String, String>, Matches > > matches() { return matches; }
	public HashMap<Tile< M >, String > tileToIdMap() { return tileToIdMap; }
	public HashMap<String, AffineModel2D> idToStitchingModel() { return idToStitchingModel; }
	public HashMap< Tile< M >, Tile< M > > tileToGroupedTile() { return tileToGroupedTile; }
	public HashMap< Tile< M >, List< Tile< M > > > groupedTileToTiles() { return groupedTileToTiles; }
	public HashSet< Integer > restarts() { return restarts; }

}
