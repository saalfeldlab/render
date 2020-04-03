package org.janelia.render.client.solver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.janelia.alignment.spec.TileSpec;

import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;

public class SolveItem< B extends Model< B > & Affine2D< B > >
{
	final public static int samplesPerDimension = 5;
	final public static AtomicInteger idGenerator = new AtomicInteger( 0 );

	final public static boolean useCosineWeight = true;

	final private int id;
	final private int minZ, maxZ;
	final private RunParameters runParams;

	// all tiles, used for solving when not grouped
	final private HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();

	// used for global solve outside
	final private HashMap<Integer, HashSet<String> > zToTileId = new HashMap<>();

	// used for saving and display
	final private HashMap<String, TileSpec> idToTileSpec = new HashMap<>();

	// contains the model as determined by the local solve
	final private HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

	// contains the model as loaded from renderer (can go right now except for debugging)
	final private HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();

	// used during the global solve only
	Tile<RigidModel2D> globalAlignBlock = null;
	AffineModel2D globalAlignAffineModel = null;

	//
	// local obly below
	//

	// used locally to map Tile back to TileId
	final private HashMap<Tile<?>, String > tileToIdMap = new HashMap<>();

	// stitching-related (local)

	// contains the model as after local stitching (tmp)
	final private HashMap<String, AffineModel2D> idToStitchingModel = new HashMap<>();

	// all grouped tiles, used for solving when stitching first
	final private HashMap< Tile< ? >, Tile< ? > > tileToGroupedTile = new HashMap<>();
	final private HashMap< Tile< ? >, List< Tile< ? > > > groupedTileToTiles = new HashMap<>();

	public SolveItem( final int minZ, final int maxZ, final RunParameters runParams )
	{
		this.id = idGenerator.getAndIncrement();

		this.minZ = minZ;
		this.maxZ = maxZ;

		this.runParams = runParams.clone();
		this.runParams.minZ = minZ;
		this.runParams.maxZ = maxZ;

		this.globalAlignBlock = new Tile< RigidModel2D >( new RigidModel2D() );
		this.globalAlignAffineModel = new AffineModel2D();
	}

	public int getId() { return id; }
	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }
	public RunParameters runParams() { return runParams; }

	public HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap() { return idToTileMap; }
	public HashMap<String, AffineModel2D> idToPreviousModel() { return idToPreviousModel; }
	public HashMap<String, TileSpec> idToTileSpec() { return idToTileSpec; }
	public HashMap<Integer, HashSet<String>> zToTileId() { return zToTileId; }
	public HashMap<String, AffineModel2D> idToNewModel() { return idToNewModel; }

	public HashMap<Tile<?>, String > tileToIdMap() { return tileToIdMap; }

	public HashMap<String, AffineModel2D> idToStitchingModel() { return idToStitchingModel; }
	public HashMap< Tile< ? >, Tile< ? > > tileToGroupedTile() { return tileToGroupedTile; }
	public HashMap< Tile< ? >, List< Tile< ? > > > groupedTileToTiles() { return groupedTileToTiles; }

	public double getWeight( final int z )
	{
		if ( useCosineWeight )
			return getCosineWeight( z );
		else
			return getLinearWeight( z );
	}

	public double getLinearWeight( final int z )
	{
		// goes from 0.0 to 1.0 as z increases to the middle, then back to 0 to the end
		return Math.max( Math.min( Math.min( (z - minZ) / ((maxZ-minZ)/2.0), (maxZ - z) / ((maxZ-minZ)/2.0) ), 1 ), 0.0000001 );
	}

	public double getCosineWeight( final int z )
	{
		return Math.max( Math.min( 1.0 - Math.cos( getLinearWeight( z ) * Math.PI/2 ), 1 ), 0.0000001 );
	}

	public ImagePlus visualizeInput() { return visualizeInput( 0.15 ); }

	public ImagePlus visualizeInput( final double scale )
	{
		try
		{
			ImagePlus imp = SolveTools.render( idToPreviousModel, idToTileSpec, 0.15 );
			imp.setTitle( "input" );
			return imp;
		}
		catch ( NoninvertibleModelException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	public ImagePlus visualizeAligned() { return visualizeAligned( 0.15 ); }

	public ImagePlus visualizeAligned( final double scale )
	{
		try
		{
			ImagePlus imp = SolveTools.render( idToNewModel, idToTileSpec, 0.15 );
			imp.setTitle( "aligned" );
			return imp;
		}
		catch ( NoninvertibleModelException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
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
			return (( SolveItem )o).getId() == getId();
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
		SolveItem s = new SolveItem( 100, 102, new RunParameters() );

		for ( int z = s.minZ(); z <= s.maxZ(); ++z )
		{
			System.out.println( z + " " + s.getWeight( z ) );
		}
	}
}