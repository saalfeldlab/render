package org.janelia.render.client.solver;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import net.imglib2.util.Pair;

/**
 * This is the data that will be serialized and shipped through Spark
 * @author spreibi
 *
 */
public class SolveItemData< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > implements Serializable
{
	private static final long serialVersionUID = -4933629169992559913L;

	private int id;
	int minZ, maxZ;

	// used for global solve outside
	final private HashMap<Integer, HashSet<String> > zToTileId = new HashMap<>();

	// used for saving and display
	final private HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<>();

	// contains the model as determined by the local solve
	final private HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

	// contains the model as loaded from renderer (can go right now except for debugging)
	final private HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();

	// stores the per-z dynamic lambdas
	final private HashMap<Integer, Double > zToDynamicLambda = new HashMap<>();

	// the errors per tile
	final HashMap< String, List< Pair< String, Double > > > idToErrorMap = new HashMap<>();

	final private G globalSolveModel;
	final private B blockSolveModel;
	final private S stitchingModel;

	public SolveItemData(
			final int id,
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final int minZ,
			final int maxZ )
	{
		this.id = id;

		this.minZ = minZ;
		this.maxZ = maxZ;

		this.globalSolveModel = globalSolveModel.copy();
		this.blockSolveModel = blockSolveModel.copy();
		this.stitchingModel = stitchingModel.copy();
	}

	protected void assignUpdatedId( final int id ) { this.id = id; }
	public int getId() { return id; }
	public int minZ() { return minZ; }
	public int maxZ() { return maxZ; }

	public G globalSolveModelInstance() { return globalSolveModel.copy(); }
	public B blockSolveModelInstance() { return blockSolveModel.copy(); }
	public S stitchingSolveModelInstance() { return stitchingModel.copy(); }

	public HashMap<String, AffineModel2D> idToPreviousModel() { return idToPreviousModel; }
	public HashMap<String, MinimalTileSpec> idToTileSpec() { return idToTileSpec; }
	public HashMap<Integer, HashSet<String>> zToTileId() { return zToTileId; }
	public HashMap<String, AffineModel2D> idToNewModel() { return idToNewModel; }
	public HashMap<Integer, Double> zToDynamicLambda() { return zToDynamicLambda; }
	public HashMap< String, List< Pair< String, Double > > > idToErrorMap() { return idToErrorMap; }

	public double maxError( final String tileId )
	{
		double maxError = -1;

		for ( final Pair< String, Double > error : idToErrorMap().get( tileId ) )
			maxError = Math.max( maxError, error.getB() );

		return maxError;
	}

	public double getWeight( final int z )
	{
		if ( SolveItem.useCosineWeight )
			return getCosineWeight( z );
		else
			return getLinearWeight( z );
	}

	public double getLinearWeight( final int z )
	{
		// goes from 0.0 to 1.0 as z increases to the middle, then back to 0 to the end
		return Math.max( Math.min( Math.min( (z - minZ()) / ((maxZ()-minZ())/2.0), (maxZ() - z) / ((maxZ()-minZ())/2.0) ), 1 ), 0.0000001 );
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
			ImagePlus imp = VisualizeTools.render( idToPreviousModel(), idToTileSpec(), 0.15 );
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
			ImagePlus imp = VisualizeTools.render( idToNewModel(), idToTileSpec(), 0.15 );
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
		else if ( o instanceof SolveItemData )
		{
			return (( SolveItemData<?,?,?> )o).getId() == getId();
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
}
