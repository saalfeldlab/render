package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.janelia.alignment.match.Matches;

import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Pair;
import org.janelia.alignment.spec.TileSpec;

/**
 * This is only instantiated inside the DistributedSolveWorker
 * 
 * @author spreibi
 *
 * @param <G>
 * @param <B>
 * @param <S>
 */
public class SolveItem< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > >
{
	final public static int samplesPerDimension = 2;
	final public static boolean useCosineWeight = false;

	final SolveItemData< G, B, S > solveItemData;

	//
	// local only variables needed for the block solve below
	//

	// all tiles, used for solving when not grouped
	final private HashMap<String, Tile< B > > idToTileMap = new HashMap<>();

	// used locally to map Tile back to TileId
	final private HashMap<Tile<B>, String > tileToIdMap = new HashMap<>();

	// stitching-related (local)

	// contains the model as after local stitching (tmp)
	final private HashMap<String, AffineModel2D> idToStitchingModel = new HashMap<>();

	// all grouped tiles, used for solving when stitching first
	final private HashMap< Tile< B >, Tile< B > > tileToGroupedTile = new HashMap<>();
	final private HashMap< Tile< B >, List< Tile< B > > > groupedTileToTiles = new HashMap<>();

	// which z layers are restarts
	final private HashSet< Integer > restarts = new HashSet<Integer>();

	// matches for error computation
	final List< Pair< Pair< String, String>, Matches > > matches = new ArrayList<>();

	public SolveItem( final SolveItemData< G, B, S > solveItemData )
	{
		this.solveItemData = solveItemData;
	}

	public SolveItemData< G, B, S > getSolveItemData() { return solveItemData; }
	public int getId() { return solveItemData.getId(); }
	public int minZ() { return solveItemData.minZ(); }
	public int maxZ() { return solveItemData.maxZ(); }

	public G globalSolveModelInstance() { return solveItemData.globalSolveModelInstance(); }
	public B blockSolveModelInstance() { return solveItemData.blockSolveModelInstance(); }
	public S stitchingSolveModelInstance( final int z ) { return solveItemData.stitchingSolveModelInstance( z ); }

	public HashMap<String, Tile< B > > idToTileMap() { return idToTileMap; }
	public HashMap<String, AffineModel2D> idToPreviousModel() { return solveItemData.idToPreviousModel(); }
	public HashMap<String, TileSpec> idToTileSpec() { return solveItemData.idToTileSpec(); }
	public HashMap<Integer, HashSet<String>> zToTileId() { return solveItemData.zToTileId(); }
	public HashMap<String, AffineModel2D> idToNewModel() { return solveItemData.idToNewModel(); }
	public HashMap<Integer, Double> zToDynamicLambda() { return solveItemData.zToDynamicLambda(); }
	public HashMap< String, List< Pair< String, Double > > > idToSolveItemErrorMap() { return solveItemData.idToSolveItemErrorMap(); }
	public List< Pair< Pair< String, String>, Matches > > matches() { return matches; }

	public HashMap<Tile<B>, String > tileToIdMap() { return tileToIdMap; }

	public HashMap<String, AffineModel2D> idToStitchingModel() { return idToStitchingModel; }
	public HashMap< Tile< B >, Tile< B > > tileToGroupedTile() { return tileToGroupedTile; }
	public HashMap< Tile< B >, List< Tile< B > > > groupedTileToTiles() { return groupedTileToTiles; }
	public HashSet< Integer > restarts() { return restarts; }

	public double getWeight( final int z )
	{
		return solveItemData.getWeight( z );
	}

	public double getLinearWeight( final int z )
	{
		return solveItemData.getLinearWeight( z );
	}

	public double getCosineWeight( final int z )
	{
		return solveItemData.getCosineWeight( z );
	}

	public ImagePlus visualizeInput() { return solveItemData.visualizeInput( 0.15 ); }

	public ImagePlus visualizeInput( final double scale )
	{
		return solveItemData.visualizeInput( scale);
	}

	public ImagePlus visualizeAligned() { return solveItemData.visualizeAligned( 0.15 ); }

	public ImagePlus visualizeAligned( final double scale )
	{
		return solveItemData.visualizeAligned( scale);
	}

	@Override
	public boolean equals( final Object o )
	{
		if ( o == null )
		{
			return false;
		}
		else if ( o instanceof SolveItem )
		{
			return (( SolveItem<?,?,?> )o).getId() == getId();
		}
		else
		{
			return false;
		}
	}

	@Override
	public int hashCode()
	{
		return getId();
	}

	public static void main( String[] args )
	{
		SolveItem< TranslationModel2D, TranslationModel2D, TranslationModel2D > s = new SolveItem<>(
				new SolveItemData< TranslationModel2D, TranslationModel2D, TranslationModel2D >( 0, null, null, null, null, null, null, null, 0, 0.0, 0.0, false, 100, 102 ) );

		for ( int z = s.minZ(); z <= s.maxZ(); ++z )
		{
			System.out.println( z + " " + s.getWeight( z ) );
		}
	}
}