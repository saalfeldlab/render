package org.janelia.render.client.multisem;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.ImageCollectionElement;
import mpicbg.stitching.fusion.Fusion;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;

public class RecapKensAlignment
{
	public static String basePath = "/nrs/hess/from_mdas/Hayworth/Wafer53/";
	public static String stitchedSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks";
	public static String rigidSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks_substrate_replaced";

	public static class TransformedImage
	{
		public String fileName;
		public int slab, z, width, height;
		public ArrayList< InvertibleBoundable > models = new ArrayList<>();
	}

	public static class TransformedZLayer
	{
		public ArrayList< TransformedImage > transformedImages;
		public int width, height;
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
		final HashMap< Integer, TransformedZLayer > models = new HashMap<>();

		// find out numSlices and indices
		final ArrayList< Integer > slices = numSlices( slab );
		final int numSlices = slices.size();

		System.out.println( "Slab " + slab + " has " + numSlices + " z-layers in Ken's alignment.");


		// TODO: scan correction
		// needs to go into Render (the wrong, inverse version Thomas did)


		// Stitching
		System.out.println( "\nSTITCHING" );

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

				final TransformedImage tI = new TransformedImage();
				tI.fileName = e.getFile().getAbsolutePath();
				tI.slab = slab;
				tI.z = z;
				tI.width = imgSizes[ i ][ 0 ];
				tI.height = imgSizes[ i ][ 1 ];
				tI.models.add( t );

				++i;

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

			final TransformedZLayer tzl = new TransformedZLayer();
			tzl.transformedImages = modelsZ;
			tzl.width = size[ 0 ];
			tzl.height = size[ 1 ];

			models.put( z, tzl );
		}


		// Rigid registration
		System.out.println( "\nRIGID REGISTRATION" );

		// this directory contains all XML's of FIJI Register_Virtual_Stack_MT
		final File dir = new File( basePath, String.format( "%s/%03d_Enhanced_Transforms", rigidSlabs, slab ) );

		System.out.println( dir.getAbsolutePath() );
		System.out.println( Arrays.toString( dir.list( (d,fn) -> fn.toLowerCase().endsWith(".xml" ) ) ) );

		// Common bounds to create common frame for all images
		final Rectangle commonBounds = new Rectangle(0, 0, models.get( slices.get( 0 ) ).width, models.get( slices.get( 0 ) ).height );

		for ( int zIndex = 0; zIndex < numSlices; ++zIndex )
		{
			final int z = slices.get( zIndex );
			final TransformedZLayer tzl = models.get( z );

			final String fn = new File( dir, String.format( "StichedImage_scan_%03d.xml", z ) ).getAbsolutePath();
			System.out.println( "Processing z=" + z + " (" + fn + ")" );

			final CoordinateTransformList<CoordinateTransform> transforms = RecapKensAlignmentTools.readCoordinateTransform( fn );

			// the second transformation (translation, only exists from the 2nd z layer on) is the bounding offset of the previous z layer
			// I think the idea is that the offset is ignored for each individual z-plane, and rather the next one is moved accordingly
			// I guess Albert did not care that a bit of the data is potentially cut off(?) <<< NO, nothing is cut, I am missing something still
			final int numTransforms = transforms.getList( new ArrayList<>() ).size();
			//System.out.println( "Number of transforms: " + numTransforms );

			// update the transformation list of all images in this z-plane
			for ( int t = 0; t < numTransforms; ++t )
			{
				final CoordinateTransform m = transforms.get( t );

				if ( mpicbg.trakem2.transform.RigidModel2D.class.isInstance( m ) )
				{
					//System.out.println( "rigid: " + m );
					models.get( z ).transformedImages.forEach( ti -> ti.models.add( (RigidModel2D)m ) );
				}
				else if ( mpicbg.trakem2.transform.TranslationModel2D.class.isInstance( m ) )
				{
					//System.out.println( "translation: " + m );
					models.get( z ).transformedImages.forEach( ti -> ti.models.add( (TranslationModel2D)m ) );
				}
				else if ( mpicbg.trakem2.transform.AffineModel2D.class.isInstance( m ) )
				{
					//System.out.println( "affine: " + m );
					models.get( z ).transformedImages.forEach( ti -> ti.models.add( (AffineModel2D)m ) );
				}
				else
				{
					throw new RuntimeException( "Don't know how to process model: " + m.getClass().getName() );
				}
			}

			final Rectangle boundingbox = RecapKensAlignmentTools.getBoundingBox( tzl.width, tzl.height, transforms );
			System.out.println( "Bounding box = " + boundingbox);

			// Update common bounds
			int min_x = commonBounds.x;
			int min_y = commonBounds.y;
			int max_x = commonBounds.x + commonBounds.width;
			int max_y = commonBounds.y + commonBounds.height;
			
			if(boundingbox.x < commonBounds.x)
				min_x = boundingbox.x;
			if(boundingbox.y < commonBounds.y)
				min_y = boundingbox.y;
			if(boundingbox.x + boundingbox.width > max_x)
				max_x = boundingbox.x + boundingbox.width;
			if(boundingbox.y + boundingbox.height > max_y)
				max_y = boundingbox.y + boundingbox.height;
			
			commonBounds.x = min_x;
			commonBounds.y = min_y;
			commonBounds.width = max_x - min_x;
			commonBounds.height = max_y - min_y;
		}
	}

	public static void main( String[] args )
	{
		reconstruct( 5 );
	}
}
