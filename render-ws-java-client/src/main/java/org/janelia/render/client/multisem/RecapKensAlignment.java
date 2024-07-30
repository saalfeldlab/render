package org.janelia.render.client.multisem;

import java.io.File;
import java.util.ArrayList;

import mpicbg.stitching.ImageCollectionElement;

public class RecapKensAlignment
{
	public static String basePath = "/nrs/hess/from_mdas/Hayworth/Wafer53/";
	public static String stitchedSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks";

	/**
	 * This already ignores slices that were dropped at a later point
	 */
	public static ArrayList< Integer > numSlices( final int slab )
	{
		final File f = new File( basePath, String.format( "%s/%03d_", stitchedSlabs, slab ) );

		final String[] files = f.list( (dir,fn) -> fn.toLowerCase().endsWith(".tif") );

		final ArrayList< Integer > zLayers = new ArrayList<>();
		for ( final String file : files )
		{
			final String z = file.substring( file.indexOf( "_scan_" )+6, file.indexOf( ".tif" ) );
			zLayers.add( Integer.parseInt( z ) );
		}

		return zLayers;
	}

	public static void reconstruct( final int slab )
	{
		// find out numSlices
		final ArrayList< Integer > slices = numSlices( slab );
		final int numSlices = slices.size();

		System.out.println( "Slab " + slab + " has " + numSlices + " z-layers in Ken's alignment.");

		// TODO: scan correction
		// needs to go into Render (the wrong, inverse version Thomas did)


		// Stitching
		System.out.println( "STITCHING" );
		for ( int zIndex = 0; zIndex < numSlices; ++zIndex )
		{
			final int z = slices.get( zIndex );

			// load the TileConfiguration.txt that contains the translations that they used to stich each z-layer
			final File f = new File( basePath, String.format( "scan_corrected_equalized_target_dir/scan_%03d/%03d_/000010", z, slab ) );//scan_corrected_equalized_target_dir/scan_001/001_/000010;
			System.out.println( "Processing: " + f.getAbsolutePath() );

			final ArrayList<ImageCollectionElement> stitchingTransforms = RecapKensAlignmentTools.getLayoutFromFile( f.getAbsolutePath(), "fiji_stitch_start.registered.txt" );
		}
	}

	public static void main( String[] args )
	{
		reconstruct( 5 );
	}
}
