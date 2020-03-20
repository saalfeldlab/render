package org.janelia.render.client.solver;

import java.util.HashMap;

import org.janelia.alignment.spec.TileSpec;

import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Tile;

public class SolveItem< B extends Model< B > & Affine2D< B > >
{
	final private int minZ, maxZ;
	final private RunParameters runParams;

	final private HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();
	final private HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();
	final private HashMap<String, TileSpec> idToTileSpec = new HashMap<>();
	final private HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

	public SolveItem( final int minZ, final int maxZ, final RunParameters runParams )
	{
		this.minZ = minZ;
		this.maxZ = maxZ;

		this.runParams = runParams.clone();
		this.runParams.minZ = minZ;
		this.runParams.maxZ = maxZ;
	}

	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }
	public RunParameters runParams() { return runParams; }

	public HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap() { return idToTileMap; }
	public HashMap<String, AffineModel2D> idToPreviousModel() { return idToPreviousModel; }
	public HashMap<String, TileSpec> idToTileSpec() { return idToTileSpec; }
	public HashMap<String, AffineModel2D> idToNewModel() { return idToNewModel; }

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
}