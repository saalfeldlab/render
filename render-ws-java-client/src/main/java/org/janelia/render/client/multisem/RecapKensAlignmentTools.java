package org.janelia.render.client.multisem;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.janelia.render.client.multisem.RecapKensAlignment.TransformedImage;

import ij.ImagePlus;
import loci.common.DebugTools;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.stitching.ImageCollectionElement;
import mpicbg.stitching.TextFileAccess;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMesh;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import stitching.utils.Log;

public class RecapKensAlignmentTools
{
	public static RandomAccessibleInterval<UnsignedByteType> render(
			final List< TransformedImage > transformedImages,
			final Interval interval )
	{
		final RandomAccessibleInterval<UnsignedByteType> output =
				Views.translate(
						ArrayImgs.unsignedBytes( interval.dimensionsAsLongArray() ),
						Intervals.minAsLongArray( interval ) );

		final List<long[][]> grid =
				Grid.create(
						interval.dimensionsAsLongArray(),
						new int[] { 512, 512 } );

		System.out.println( "num blocks = " + grid.size() );

		long time = System.currentTimeMillis();

		// open images & assemble transforms into a single one
		final ArrayList< Img< UnsignedByteType > > images = new ArrayList<>();
		final ArrayList< AffineModel2D > models = new ArrayList<>();
		final ArrayList< Interval > boundingBoxes = new ArrayList<>();

		// disable bioformats logging
		DebugTools.setRootLevel("OFF");

		for ( final TransformedImage tI : transformedImages )
		{
			final ImagePlus imp = tI.e.open( false );
			images.add( ImageJFunctions.wrapByte( imp ) );

			final AffineModel2D fullModel = new AffineModel2D();
			final AffineModel2D tmp = new AffineModel2D();

			for ( final AbstractAffineModel2D< ? > affine : tI.models )
			{
				tmp.set( affine.createAffine() );
				fullModel.preConcatenate( tmp );
			}

			models.add( fullModel.createInverse() ); // we only need the inverse

			final double[] min = new double[] { 0, 0 };
			final double[] max = new double[] { imp.getWidth() - 1, imp.getHeight() - 1 };
			fullModel.estimateBounds( min, max );

			boundingBoxes.add( new FinalInterval(
					new long[] { Math.round( Math.floor( min[ 0 ] ) ), Math.round( Math.floor( min[ 1 ] ) ) },
					new long[] { Math.round( Math.ceil( max[ 0 ] ) ), Math.round( Math.ceil( max[ 1 ] ) ) }) );
		}

		System.out.println( "loaded images (took " + ( System.currentTimeMillis() - time )/1000 + " sec), starting ... " );

		time = System.currentTimeMillis();
		final ExecutorService ex = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );

		//
		// fuse data block by block
		//
		ex.submit(() ->
			grid.parallelStream().forEach(
					gridBlock -> {
						try {
							final Interval block =
									Intervals.translate(
											Intervals.translate(
													new FinalInterval( gridBlock[1] ), // blocksize
													gridBlock[0] ), // block offset
											Intervals.minAsLongArray( interval ) ); // offset of global interval

							final RandomAccessibleInterval< UnsignedByteType > target = Views.interval( output, block );

							// test which which images we actually overlap
							final ArrayList< RealRandomAccess< UnsignedByteType > > myImages = new ArrayList<>();
							final ArrayList< AffineModel2D > myModels = new ArrayList<>();
							final ArrayList< Interval > myRawIntervals = new ArrayList<>();

							for ( int i = 0; i < images.size(); ++i )
							{
								if ( !Intervals.isEmpty( Intervals.intersect( target, boundingBoxes.get( i ) ) ) )
								{
									myImages.add( Views.interpolate( Views.extendZero( images.get( i ) ), new NLinearInterpolatorFactory<>() ).realRandomAccess() );
									myModels.add( models.get( i ) );
									myRawIntervals.add( new FinalInterval( images.get( i ) ) );
								}
							}

							final Cursor< UnsignedByteType > cursor = Views.iterable( target ).localizingCursor();
							final double[] tmp = new double[ cursor.numDimensions() ];

							while ( cursor.hasNext() )
							{
								final UnsignedByteType type = cursor.next();

								for ( int i = 0; i < myImages.size(); ++i )
								{
									cursor.localize( tmp );
									myModels.get( i ).applyInPlace( tmp );

									if ( Intervals.contains( myRawIntervals.get( i ), new RealPoint( tmp ) ) )
									{
										myImages.get( i ).setPosition( tmp );
										type.set( myImages.get( i ).get() );
										break; // just take the first best
									}
								}
							}

							//final int value = rnd.nextInt( 255 );
							//Views.iterable( target ).forEach( type -> type.set( value ) );
						}
						catch (Exception e) 
						{
							System.out.println( "Error fusing block offset=" + Util.printCoordinates( gridBlock[0] ) + "' ... " );
							e.printStackTrace();
						}
					} )
			);

		try
		{
			ex.shutdown();
			ex.awaitTermination( Long.MAX_VALUE, TimeUnit.HOURS);
		}
		catch (InterruptedException e)
		{
			System.out.println( "Failed to fuse. Error: " + e );
			e.printStackTrace();
			return null;
		}

		//System.out.println( "Saved, e.g. view with './n5-view -i " + n5Path + " -d " + n5Dataset );
		System.out.println( "Fused, took: " + (System.currentTimeMillis() - time ) + " ms." );

		return output;
	}

	/**
	 * Get StageIdPlus1 from slab.
	 *
	 * Nomenclature:
	 * stageId:  order in which the wafer is traversed during acquisition. 0-indexed
	 * serialId: order in which the slabs were mechanically cut. 0-indexed.
	 * 			 This repo uses the variable slab to refer to the serialId,
	 * 			 but slab is 1-indexed. So we have serialId = slab - 1
	 * All the IDs defined in the .csv file are 0-indexed.
	 * 
	 * @param magCFile .csv file, e.g. File( root, "scan_005.csv" )
	 * @param slab slab represents the serial order. See nomenclature above for details.
	 * @return the stage ID + 1.
	 */
	public static int findStageIdPlus1( final File magCFile, final int slab )
	{
		try
		{
			final BufferedReader reader = new BufferedReader(new FileReader( magCFile ));

			String line = reader.readLine().trim();
			int id_line = -1; // for a 0-indexed id_line after skipping the header

			while (line != null)
			{
				if ( !line.startsWith( "magc_to_serial" ) && line.length() > 1 ) // header or empty, ignore
				{
					if ( id_line == slab - 1 )
					{
						String[] entries = line.split( "," );
						final int stageIdPlus1 = Integer.parseInt( entries[ 4 ]);
						reader.close();
						return stageIdPlus1

					}
				}

				++id_line;
				line = reader.readLine();
			}

			reader.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		return Integer.MIN_VALUE;
	}

	/**
	 * Get slab from stageIdPlus1.
	 *
	 * See findStageIdPlus1 for nomenclature.
	 * 
	 * @param magCFile .csv file, e.g. File( root, "scan_005.csv" )
	 * @param stageIdPlus1 the stageIdPlus1
	 * @return slab it refers to the serial order. We have id_serial = slab - 1
	 */
	public static int findSlab( final File magCFile, final int stageIdPlus1 )
	{
		try
		{
			final BufferedReader reader = new BufferedReader(new FileReader( magCFile ));

			String line = reader.readLine().trim();
			int id_line = -1; // for a 0-indexed id_line after skipping the header 

			while (line != null)
			{
				if ( !line.startsWith( "magc_to_serial" ) && line.length() > 1 ) // header or empty, ignore
				{
					if ( id_line == stageIdPlus1 - 1 )
					{
						String[] entries = line.split( "," );
						final int stageIdPlus1 = Integer.parseInt( entries[ 5 ]);
						reader.close();
						return stageIdPlus1

					}
				}

				++id_line;
				line = reader.readLine();
			}

			reader.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		return Integer.MIN_VALUE;
	}


	/**
	 * Get slabAngle from the stageIdPlus1.
	 *
	 * @param magCFile .csv file, e.g. File( root, "scan_005.csv" )
	 * @param stageIdPlus1 the stage ID + 1. See nomenclature in findStageIdPlus1.
	 * @return the angle of the slab, in degrees
	 */

	public static double getSlabAngle( final File magCFile, final int stageIdPlus1 )
	{
		try
		{
			final int slab = findSlab(magCFile, stageIdPlus1);
			final BufferedReader reader = new BufferedReader(new FileReader( magCFile ));

			String line = reader.readLine().trim();
			int id_line = -1;

			while (line != null)
			{
				if ( !line.startsWith( "magc_to_serial" ) && line.length() > 1 ) // header or empty, ignore
				{
					if ( id_line == slab - 1 )
					{
						String[] entries = line.split( "," );
						final int angle = Integer.parseInt( entries[ 6 ]);
						reader.close();
						return angle

					}
				}

				++id_line;
				line = reader.readLine();

			}

			reader.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		return Double.NaN;
	}

	/*
	 * From stitching code
	 */
	public static ArrayList< ImageCollectionElement > getLayoutFromFile( final String directory, final String layoutFile )
	{
		final ArrayList< ImageCollectionElement > elements = new ArrayList< ImageCollectionElement >();
		int dim = -1;
		int index = 0;
		boolean multiSeries = false;
		// A HashMap using the filename (including the full path) as the key is
		// used to access the individual tiles of a multiSeriesFile. This way
		// it's very easy to check if a file has already been opened. Note that
		// the map doesn't get used in the case of single series files below!
		// TODO: check performance on large datasets! Use an array for the
		// ImagePlus'es otherwise and store the index number in the hash map!
		//Map<String, ImagePlus[]> multiSeriesMap = new HashMap<String, ImagePlus[]>();
		String pfx = "Stitching_Grid.getLayoutFromFile: ";
		try {
			final BufferedReader in = TextFileAccess.openFileRead( new File( directory, layoutFile ) );
			if ( in == null ) {
				Log.error(pfx + "Cannot find tileconfiguration file '" + new File( directory, layoutFile ).getAbsolutePath() + "'");
				return null;
			}
			int lineNo = 0;
			pfx += "Line ";
			while ( in.ready() ) {
				String line = in.readLine().trim();
				lineNo++;
				if ( !line.startsWith( "#" ) && line.length() > 3 ) {
					if ( line.startsWith( "dim" ) ) {  // dimensionality parsing
						String entries[] = line.split( "=" );
						if ( entries.length != 2 ) {
							Log.error(pfx + lineNo + " does not look like [ dim = n ]: " + line);
							return null;						
						}
						
						try {
							dim = Integer.parseInt( entries[1].trim() );
						}
						catch ( NumberFormatException e ) {
							Log.error(pfx + lineNo + ": Cannot parse dimensionality: " + entries[1].trim());
							return null;														
						}

					} else if ( line.startsWith( "multiseries" ) )  {
						String entries[] = line.split( "=" );
						if ( entries.length != 2 ) {
							Log.error(pfx + lineNo + " does not look like [ multiseries = (true|false) ]: " + line);
							return null;
						}

						if (entries[1].trim().equals("true")) {
							multiSeries = true;
							Log.info(pfx + lineNo + ": parsing MultiSeries configuration.");
						}

					} else {  // body parsing (tiles + coordinates)
						if ( dim < 0 ) {
							Log.error(pfx + lineNo + ": Header missing, should look like [dim = n], but first line is: " + line);
							return null;							
						}
						
						if ( dim < 2 || dim > 3 ) {
							Log.error(pfx + lineNo + ": only dimensions of 2 and 3 are supported: " + line);
							return null;							
						}
						
						// read image tiles
						String entries[] = line.split(";");
						if (entries.length != 3) {
							Log.error(pfx + lineNo + " does not have 3 entries! [fileName; seriesNr; (x,y,...)]");
							return null;						
						}

						String imageName = entries[0].trim();
						if (imageName.length() == 0) {
							Log.error(pfx + lineNo + ": You have to give a filename [fileName; ; (x,y,...)]: " + line);
							return null;						
						}
						
						int seriesNr = -1;
						if (multiSeries) {
							String imageSeries = entries[1].trim();  // sub-volume (series nr)
							if (imageSeries.length() == 0) {
								Log.info(pfx + lineNo + ": Series index required [fileName; series; (x,y,...)" );
							} else {
								try {
									seriesNr = Integer.parseInt( imageSeries );
									Log.info(pfx + lineNo + ": Series nr (sub-volume): " + seriesNr);
								}
								catch ( NumberFormatException e ) {
									Log.error(pfx + lineNo + ": Cannot parse series nr: " + imageSeries);
									return null;
								}
							}
						}

						String point = entries[2].trim();  // coordinates
						if (!point.startsWith("(") || !point.endsWith(")")) {
							Log.error(pfx + lineNo + ": Wrong format of coordinates: (x,y,...): " + point);
							return null;
						}
						
						point = point.substring(1, point.length() - 1);  // crop enclosing braces
						String points[] = point.split(",");
						if (points.length != dim) {
							Log.error(pfx + lineNo + ": Wrong format of coordinates: (x,y,z,...), dim = " + dim + ": " + point);
							return null;
						}
						final float[] offset = new float[ dim ];
						for ( int i = 0; i < dim; i++ ) {
							try {
								offset[ i ] = Float.parseFloat( points[i].trim() ); 
							}
							catch (NumberFormatException e) {
								Log.error(pfx + lineNo + ": Cannot parse number: " + points[i].trim());
								return null;							
							}
						}
						
						// now we can assemble the ImageCollectionElement:
						ImageCollectionElement element = new ImageCollectionElement(
								new File( directory, imageName ), index++ );
						element.setDimensionality( dim );
						if ( dim == 3 )
							element.setModel( new TranslationModel3D() );
						else
							element.setModel( new TranslationModel2D() );
						element.setOffset( offset );

						if (multiSeries) {
							throw new RuntimeException( "not supported");
							/*
							final String imageNameFull = element.getFile().getAbsolutePath();
							if (multiSeriesMap.get(imageNameFull) == null) {
								Log.info(pfx + lineNo + ": Loading MultiSeries file: " + imageNameFull);
								multiSeriesMap.put(imageNameFull, openBFDefault(imageNameFull));
							}
							element.setImagePlus(multiSeriesMap.get(imageNameFull)[seriesNr]);*/
						}

						elements.add( element );
					}
				}
			}
		}
		catch ( IOException e ) {
			Log.error( "Stitching_Grid.getLayoutFromFile: " + e );
			return null;
		}

		return elements;
	}

	// from: register_virtual_stack_slices/src/main/java/register_virtual_stack/Transform_Virtual_Stack_MT.java
	/**
	 * Read coordinate transform from file (generated in Register_Virtual_Stack)
	 *
	 * @param filename  complete file name (including path)
	 * @return true if the coordinate transform was properly read, false otherwise.
	 */
	public static CoordinateTransformList<CoordinateTransform> readCoordinateTransform( String filename )
	{
		final CoordinateTransformList<CoordinateTransform> ctl = new CoordinateTransformList<CoordinateTransform>();
		try 
		{
			final FileReader fr = new FileReader(filename);
			final BufferedReader br = new BufferedReader(fr);
			String line = null;
			while ((line = br.readLine()) != null) 
			{
				int index = -1;
				if( (index = line.indexOf("class=")) != -1)
				{
					// skip "class"
					index+= 5;
					// read coordinate transform class name
					final int index2 = line.indexOf("\"", index+2); 
					final String ct_class = line.substring(index+2, index2);
					@SuppressWarnings("deprecation")
					final CoordinateTransform ct = (CoordinateTransform) Class.forName(ct_class).newInstance();
					// read coordinate transform info
					final int index3 = line.indexOf("=", index2+1);
					final int index4 = line.indexOf("\"", index3+2); 
					final String data = line.substring(index3+2, index4);
					ct.init(data);
					ctl.add(ct);
				}
			}
			br.close();
		
		} catch (FileNotFoundException e) {
			System.err.println("File not found exception" + e);
			
		} catch (IOException e) {
			System.err.println("IOException exception" + e);
			
		} catch (NumberFormatException e) {
			System.err.println("Number format exception" + e);
			
		} catch (InstantiationException e) {
			System.err.println("Instantiation exception" + e);
			
		} catch (IllegalAccessException e) {
			System.err.println("Illegal access exception" + e);
			
		} catch (ClassNotFoundException e) {
			System.err.println("Class not found exception" + e);
			
		}
		return ctl;
	}

	// adapted from register_virtual_stack_slices/src/main/java/register_virtual_stack/Register_Virtual_Stack_MT.applyTransformAndSave()
	// to only return the bounding box
	public static Rectangle getBoundingBox(
			final int width,
			final int height,
			final CoordinateTransform transform)
	{
		// Open next image
		//final ImagePlus imp2 = readImage(source_dir + file_name);

		// Calculate transform mesh
		final TransformMesh mesh = new TransformMesh(transform, 32, width, height);
		//TransformMeshMapping mapping = new TransformMeshMapping(mesh);

		// Create interpolated deformed image with black background
		//imp2.getProcessor().setValue(0);
		//final ImageProcessor ip2 = interpolate ? mapping.createMappedImageInterpolated(imp2.getProcessor()) : mapping.createMappedImage(imp2.getProcessor()); 
		//imp2.setProcessor(imp2.getTitle(), ip2);
		
		//imp2.show();

		// Accumulate bounding boxes, so in the end they can be reopened and re-saved with an enlarged canvas.
		final Rectangle currentBounds = mesh.getBoundingBox();
		return currentBounds;
		//bounds[i] = currentBounds;
		
		// Save target image
		//return new FileSaver(imp2).saveAsTiff(makeTargetPath(target_dir, file_name));
	}

	/**
	 * copied for convenience
	 *
	 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
	 */
	public static class Grid
	{
		private Grid() {}

		/*
		 * Crops the dimensions of a {@link DataBlock} at a given offset to fit
		 * into and {@link Interval} of given dimensions.  Fills long and int
		 * version of cropped block size.  Also calculates the grid raster position
		 * assuming that the offset divisible by block size without remainder.
		 *
		 * @param max
		 * @param offset
		 * @param blockSize
		 * @param croppedBlockSize
		 * @param intCroppedBlockDimensions
		 * @param gridPosition
		 */
		static void cropBlockDimensions(
				final long[] dimensions,
				final long[] offset,
				final int[] outBlockSize,
				final int[] blockSize,
				final long[] croppedBlockSize,
				final long[] gridPosition) {

			for (int d = 0; d < dimensions.length; ++d) {
				croppedBlockSize[d] = Math.min(blockSize[d], dimensions[d] - offset[d]);
				gridPosition[d] = offset[d] / outBlockSize[d];
			}
		}

		/*
		 * Create a {@link List} of grid blocks that, for each grid cell, contains
		 * the world coordinate offset, the size of the grid block, and the
		 * grid-coordinate offset.  The spacing for input grid and output grid
		 * are independent, i.e. world coordinate offsets and cropped block-sizes
		 * depend on the input grid, and the grid coordinates of the block are
		 * specified on an independent output grid.  It is assumed that
		 * gridBlockSize is an integer multiple of outBlockSize.
		 *
		 * @param dimensions
		 * @param gridBlockSize
		 * @param outBlockSize
		 * @return
		 */
		public static List<long[][]> create(
				final long[] dimensions,
				final int[] gridBlockSize,
				final int[] outBlockSize) {

			final int n = dimensions.length;
			final ArrayList<long[][]> gridBlocks = new ArrayList<>();

			final long[] offset = new long[n];
			final long[] gridPosition = new long[n];
			final long[] longCroppedGridBlockSize = new long[n];
			for (int d = 0; d < n;) {
				cropBlockDimensions(dimensions, offset, outBlockSize, gridBlockSize, longCroppedGridBlockSize, gridPosition);
					gridBlocks.add(
							new long[][]{
								offset.clone(),
								longCroppedGridBlockSize.clone(),
								gridPosition.clone()
							});

				for (d = 0; d < n; ++d) {
					offset[d] += gridBlockSize[d];
					if (offset[d] < dimensions[d])
						break;
					else
						offset[d] = 0;
				}
			}
			return gridBlocks;
		}

		/*
		 * Create a {@link List} of grid blocks that, for each grid cell, contains
		 * the world coordinate offset, the size of the grid block, and the
		 * grid-coordinate offset.
		 *
		 * @param dimensions
		 * @param blockSize
		 * @return
		 */
		public static List<long[][]> create(
				final long[] dimensions,
				final int[] blockSize) {

			return create(dimensions, blockSize, blockSize);
		}


		/*
		 * Create a {@link List} of grid block offsets in world coordinates
		 * covering an {@link Interval} at a given spacing.
		 *
		 * @param interval
		 * @param spacing
		 * @return
		 */
		public static List<long[]> createOffsets(
				final Interval interval,
				final int[] spacing) {

			final int n = interval.numDimensions();
			final ArrayList<long[]> offsets = new ArrayList<>();

			final long[] offset = Intervals.minAsLongArray(interval);
			for (int d = 0; d < n;) {
				offsets.add(offset.clone());

				for (d = 0; d < n; ++d) {
					offset[d] += spacing[d];
					if (offset[d] <= interval.max(d))
						break;
					else
						offset[d] = interval.min(d);
				}
			}
			return offsets;
		}

		/*
		 * Returns the grid coordinates of a given offset for a min coordinate and
		 * a grid spacing.
		 *
		 * @param offset
		 * @param min
		 * @param spacing
		 * @return
		 */
		public static long[] gridCell(
				final long[] offset,
				final long[] min,
				final int[] spacing) {

			final long[] gridCell = new long[offset.length];
			Arrays.setAll(gridCell, i -> (offset[i] - min[i]) / spacing[i]);
			return gridCell;
		}

		/*
		 * Returns the long coordinates smaller or equal scaled double coordinates.
		 *
		 * @param doubles
		 * @param scale
		 * @return
		 */
		public static long[] floorScaled(final double[] doubles, final double scale) {

			final long[] floorScaled = new long[doubles.length];
			Arrays.setAll(floorScaled, i -> (long)Math.floor(doubles[i] * scale));
			return floorScaled;
		}

		/*	
		 * Returns the long coordinate greater or equal scaled double coordinates.
		 *
		 * @param doubles
		 * @param scale
		 * @return
		 */
		public static long[] ceilScaled(final double[] doubles, final double scale) {

			final long[] ceilScaled = new long[doubles.length];
			Arrays.setAll(ceilScaled, i -> (long)Math.ceil(doubles[i] * scale));
			return ceilScaled;
		}
	}
}
