package org.janelia.render.client.multisem;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import ij.ImageJ;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.ImageCollectionElement;
import mpicbg.stitching.fusion.Fusion;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class RecapKensAlignment
{
	public static String basePath = "/nrs/hess/from_mdas/Hayworth/Wafer53/";
	public static String stitchedSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks";
	public static String rigidSlabs = "/scan_corrected_equalized_target_dir/stitched_stacks_substrate_replaced";
	public static String magC = "/nrs/hess/from_mdas/ufomsem/acquisition/base/wafer_53/ordering";

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
		public Rectangle boundsVirtualStackAlignment;
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


		//
		// Stitching
		//
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

			// if we wanted to render, should be zero-min with the following size
			tzl.width = size[ 0 ];
			tzl.height = size[ 1 ];

			models.put( z, tzl );
		}


		//
		// Rigid registration (Register_Virtual_Stack)
		//
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
			// it's an additional movement to correct for the offset of the previous plane that is baked into the transformation (somehow);
			// each "applyTransformAndSave()" applies the transform and sets it's min to (0,0)
			// NOTE: Register_Virtual_Stack applies the transformation using meshes, so slight differences are expected(!)

			final int numTransforms = transforms.getList( new ArrayList<>() ).size();
			//System.out.println( "Number of transforms: " + numTransforms );

			tzl.boundsVirtualStackAlignment = RecapKensAlignmentTools.getBoundingBox( tzl.width, tzl.height, transforms );
			final TranslationModel2D bbTransform = new TranslationModel2D();
			bbTransform.set( -tzl.boundsVirtualStackAlignment.getMinX(), -tzl.boundsVirtualStackAlignment.getMinY() );

			System.out.println( "Bounding box = " + tzl.boundsVirtualStackAlignment);

			// update the transformation list of all images in this z-plane (apply transform and shift min to 0,0)
			// TODO: is the order of transforms correct?
			for ( int t = 0; t < numTransforms; ++t )
			{
				final CoordinateTransform m = transforms.get( t );

				if ( mpicbg.trakem2.transform.RigidModel2D.class.isInstance( m ) )
					tzl.transformedImages.forEach( ti -> ti.models.add( (RigidModel2D)m ) );
				else if ( mpicbg.trakem2.transform.TranslationModel2D.class.isInstance( m ) )
					tzl.transformedImages.forEach( ti -> ti.models.add( (TranslationModel2D)m ) );
				else if ( mpicbg.trakem2.transform.AffineModel2D.class.isInstance( m ) )
					tzl.transformedImages.forEach( ti -> ti.models.add( (AffineModel2D)m ) );
				else
					throw new RuntimeException( "Don't know how to process model: " + m.getClass().getName() );
			}

			tzl.transformedImages.forEach( ti -> ti.models.add( bbTransform ) );

			// Update common bounds
			int min_x = commonBounds.x;
			int min_y = commonBounds.y;
			int max_x = commonBounds.x + commonBounds.width;
			int max_y = commonBounds.y + commonBounds.height;
			
			if(tzl.boundsVirtualStackAlignment.x < commonBounds.x)
				min_x = tzl.boundsVirtualStackAlignment.x;
			if(tzl.boundsVirtualStackAlignment.y < commonBounds.y)
				min_y = tzl.boundsVirtualStackAlignment.y;
			if(tzl.boundsVirtualStackAlignment.x + tzl.boundsVirtualStackAlignment.width > max_x)
				max_x = tzl.boundsVirtualStackAlignment.x + tzl.boundsVirtualStackAlignment.width;
			if(tzl.boundsVirtualStackAlignment.y + tzl.boundsVirtualStackAlignment.height > max_y)
				max_y = tzl.boundsVirtualStackAlignment.y + tzl.boundsVirtualStackAlignment.height;
			
			commonBounds.x = min_x;
			commonBounds.y = min_y;
			commonBounds.width = max_x - min_x;
			commonBounds.height = max_y - min_y;
		}

		System.out.println("\nFinal common bounding box = [x=" + commonBounds.x + ", y=" + commonBounds.y + "; " + commonBounds.width + "x" + commonBounds.height + "]");

		// apply common bounding box to all; equivalent to resizeAndSaveImage()
		for ( int zIndex = 0; zIndex < numSlices; ++zIndex )
		{
			final int z = slices.get( zIndex );
			final TransformedZLayer tzl = models.get( z );

			final TranslationModel2D commonBBTransform = new TranslationModel2D();
			commonBBTransform.set( tzl.boundsVirtualStackAlignment.x - commonBounds.x, tzl.boundsVirtualStackAlignment.y - commonBounds.y ); // strictly positive

			tzl.transformedImages.forEach( ti -> ti.models.add( commonBBTransform ) );

			System.out.println( "Adjustment for " + slices.get( zIndex ) + ": " + commonBBTransform );
			/*
			b.x -= commonBounds.x;
			b.y -= commonBounds.y; // strictly positive
			*/
		}

		// if we wanted to render, should be zero-min with the following size
		int slabWidth = commonBounds.width;
		int slabHeight = commonBounds.height;


		//
		// Global Rotation
		//

		// canvas size 22000x22000 (position=center)
		// half pixels are processed in int's, e.g. 21350x18603 >> 22000x22000 (ij.plugin.CanvasResizer):

		final int xC = (22000 - slabWidth)/2;// offset for centered
		final int yC = (22000 - slabHeight)/2;// offset for centered

		final TranslationModel2D canvasResizeModel = new TranslationModel2D();
		canvasResizeModel.set( xC, yC );

		for ( int zIndex = 0; zIndex < numSlices; ++zIndex )
			models.get( slices.get( zIndex ) ).transformedImages.forEach( ti -> ti.models.add( canvasResizeModel ) );

		// if we wanted to render, should be zero-min with the following size
		slabWidth = slabHeight = 22000;

		// rotate by MagC angle (grid=1)
		// (ij.plugin.filter.Rotator)

		// first load the rotation angle
		final File file = new File( magC, "scan_005.csv" );
		System.out.println( "Loading: " + file.getAbsolutePath() );

		final double angle = RecapKensAlignmentTools.parseMagCFile( file, slab );
		System.out.println( "Angle: " + angle );

		for ( int zIndex = 0; zIndex < numSlices; ++zIndex )
		{
			final int z = slices.get( zIndex );
			final TransformedZLayer tzl = models.get( z );

			final double centerX = (slabWidth-1)/2.0;
			final double centerY = (slabHeight-1)/2.0;

			final TranslationModel2D toOrigin = new TranslationModel2D();
			final RigidModel2D rotate = new RigidModel2D();
			final TranslationModel2D fromOrigin = new TranslationModel2D();
	
			toOrigin.set( -centerX, -centerY );
			rotate.set( Math.toRadians( angle ), 0, 0 ); // TODO: is this angle correct?
			fromOrigin.set( centerX, centerY );

			tzl.transformedImages.forEach( ti -> {
				ti.models.add( toOrigin );
				ti.models.add( rotate );
				ti.models.add( fromOrigin );
			} );
		}
	}

	public static void main( String[] args )
	{
		new ImageJ();
		RandomAccessibleInterval<UnsignedByteType> img = RecapKensAlignmentTools.render( null, new FinalInterval( new long[] { -100, -200 }, new long[] { 3000, 3000 } ) );
		ImageJFunctions.show( img );

		// 5 is not the slab but some serial number I believe, we need to figure out the actual slab number from that
		//reconstruct( 5 );
	}
}
