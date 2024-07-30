package org.janelia.render.client.multisem;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import loci.common.DebugTools;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.ImageCollectionElement;
import mpicbg.stitching.fusion.Fusion;

public class RecapKensAlignment
{
	public static String basePath = "/nrs/hess/from_mdas/Hayworth/Wafer53/";
	public static String stitchedSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks";

	public static class TransformedImage
	{
		public String fileName;
		public int slab;
		public int z;
		public ArrayList< InvertibleBoundable > models = new ArrayList<>();
	}

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
		final HashMap< Integer, ArrayList< TransformedImage > > models = new HashMap<>();

		// find out numSlices and indices
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

			// all models extracted for this z-layer
			final ArrayList< TransformedImage > modelsZ = new ArrayList<>();

			// load the TileConfiguration.txt that contains the translations that they used to stich each z-layer
			final File f = new File( basePath, String.format( "scan_corrected_equalized_target_dir/scan_%03d/%03d_/000010", z, slab ) );//scan_corrected_equalized_target_dir/scan_001/001_/000010;
			System.out.println( "Processing: " + f.getAbsolutePath() );

			final ArrayList<ImageCollectionElement> stitchingTransforms =
					RecapKensAlignmentTools.getLayoutFromFile( f.getAbsolutePath(), "fiji_stitch_start.registered.txt" );

			final int[][] imgSizes = new int[ stitchingTransforms.size() ][ 2 ];
			final ArrayList<InvertibleBoundable> stitchingModels = new ArrayList<>();

			int i = 0;

			for ( final ImageCollectionElement e : stitchingTransforms )
			{
				// transform for each image
				final TranslationModel2D t = new TranslationModel2D();
				t.set( e.getOffset( 0 ), e.getOffset( 1 ) );

				imgSizes[ i ][ 0 ] = 1996;
				imgSizes[ i ][ 1 ] = 1748;
				stitchingModels.add( t );
				++i;

				final TransformedImage tI = new TransformedImage();
				tI.fileName = e.getFile().getAbsolutePath();
				tI.slab = slab;
				tI.z = z;
				tI.models.add( t );

				modelsZ.add( tI );
				//System.out.println( e.getFile().getAbsolutePath() );
				//System.out.println( Arrays.toString( e.getOffset() ) + ", " + e.getDimensionality() );
				//System.out.println( Arrays.toString( e.getDimensions() ) );
			}

			final double[] offset = new double[ 2 ];
			final int[] size = new int[ 2 ];

			Fusion.estimateBounds( offset, size, imgSizes, stitchingModels, 2 );

			System.out.println( "offset = " + Arrays.toString( offset ) + ", size = " + Arrays.toString( size ));

			// the transformation of the bounding box (for each image)
			// it is negative because the actual coordinates are moved to [0,0]
			final TranslationModel2D bbStitching = new TranslationModel2D();
			bbStitching.set( -offset[ 0 ], -offset[ 1 ] );

			modelsZ.forEach( m -> m.models.add( bbStitching ) );
			models.put( z, modelsZ );
		}

	}

	public static void main( String[] args )
	{
		reconstruct( 5 );
	}
}
