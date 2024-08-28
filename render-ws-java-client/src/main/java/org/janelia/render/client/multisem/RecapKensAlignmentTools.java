package org.janelia.render.client.multisem;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.janelia.alignment.util.Grid;
import org.janelia.render.client.multisem.RecapKensAlignment.TransformedImage;

import ij.ImagePlus;
import loci.common.DebugTools;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;
import mpicbg.trakem2.transform.TranslationModel3D;
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
import net.imglib2.view.Views;
import stitching.utils.Log;

public class RecapKensAlignmentTools {

	public static class SlabInfo {
		public final int serialId;
		public final int stageId;
		public final double angle;

		public SlabInfo(final int serialId, final int stageId, final double angle) {
			this.serialId = serialId;
			this.stageId = stageId;
			this.angle = angle;
		}

		public int slabNumber() {
			return serialId;
		}

		public int stageIdPlus1() {
			return stageId + 1;
		}
	}

	public static RandomAccessibleInterval<UnsignedByteType> render(
			final List< TransformedImage > transformedImages,
			final Interval interval )
	{
		final RandomAccessibleInterval<UnsignedByteType> output =
				Views.translate(
						ArrayImgs.unsignedBytes( interval.dimensionsAsLongArray() ),
						Intervals.minAsLongArray( interval ) );

		final List<Grid.Block> grid =
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

			final AffineModel2D concatenatedModel = concatenateModels(tI);
			models.add(concatenatedModel.createInverse()); // we only need the inverse

			final double[] min = new double[] { 0, 0 };
			final double[] max = new double[] { imp.getWidth() - 1, imp.getHeight() - 1 };
			concatenatedModel.estimateBounds(min, max);

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
							final Interval block =
									Intervals.translate(
											Intervals.translate(
													new FinalInterval(gridBlock.dimensions),
													gridBlock.offset),
											Intervals.minAsLongArray( interval ) ); // offset of global interval

							final RandomAccessibleInterval< UnsignedByteType > target = Views.interval( output, block );

							// test which images we actually overlap
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
					} )
			);

		try {
			ex.shutdown();
			ex.awaitTermination( Long.MAX_VALUE, TimeUnit.HOURS);
		} catch (final InterruptedException e) {
			throw new RuntimeException("Failed to fuse.", e);
		}

		//System.out.println( "Saved, e.g. view with './n5-view -i " + n5Path + " -d " + n5Dataset );
		System.out.println( "Fused, took: " + (System.currentTimeMillis() - time ) + " ms." );

		return output;
	}

	public static AffineModel2D concatenateModels(final TransformedImage transformedImage) {
		final AffineModel2D fullModel = new AffineModel2D();
		final AffineModel2D singleModel = new AffineModel2D();

		for (final AbstractAffineModel2D<?> currentModel : transformedImage.models) {
			singleModel.set(currentModel.createAffine());
			fullModel.preConcatenate(singleModel);
		}
		return fullModel;
	}

	/**
	 * Get {@link SlabInfo} from slab. Nomenclature:
	 * stageId:  order in which the slabs are traversed during acquisition. 0-indexed
	 * serialId: order in which the slabs were mechanically cut. 0-indexed.
	 * The IDs defined in the .csv file are 0-indexed.
	 *
	 * @param magCFile .csv file, e.g. File(root, "scan_005.csv")
	 * @param slab the slab number in serial order; see nomenclature above for details.
	 * @return {@link SlabInfo} with serialId, stageId, and angle.
	 */
	public static SlabInfo getSlabInfo(final File magCFile, final int slab) {
		try (final Stream<String> lines = Files.lines(magCFile.toPath())) {
			// There's one header line and slab is 0-indexed
			final String targetLine = lines.skip(slab + 1).findFirst().orElseThrow();

			// Important columns (0-based) in the .csv file are: 4 (serial_to_stage) and 6 (angle)
			final String[] entries = targetLine.split(",");
			final int stageId = Integer.parseInt(entries[4]);
			final double angle = Double.parseDouble(entries[6]);

			return new SlabInfo(slab, stageId, angle);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

	/*
	 * From stitching code
	 */
	public static ArrayList< ImageCollectionElement > getLayoutFromFile( final String directory, final String layoutFile )
	{
		final ArrayList< ImageCollectionElement > elements = new ArrayList<>();
		int dim = -1;
		int index = 0;
		boolean multiSeries = false;
		// A HashMap using the filename (including the full path) as the key is
		// used to access the individual tiles of a multiSeriesFile. This way
		// it's very easy to check if a file has already been opened. Note that
		// the map doesn't get used in the case of single series files below!
		// TODO: check performance on large datasets! Use an array for the
		// ImagePluses otherwise and store the index number in the hash map!
		//Map<String, ImagePlus[]> multiSeriesMap = new HashMap<String, ImagePlus[]>();
		String pfx = "Stitching_Grid.getLayoutFromFile: ";
		try (final BufferedReader in = TextFileAccess.openFileRead(new File(directory, layoutFile))) {
			if ( in == null ) {
				Log.error(pfx + "Cannot find tileconfiguration file '" + new File( directory, layoutFile ).getAbsolutePath() + "'");
				return null;
			}
			int lineNo = 0;
			pfx += "Line ";
			while ( in.ready() ) {
				final String line = in.readLine().trim();
				lineNo++;
				if ( !line.startsWith( "#" ) && line.length() > 3 ) {
					if ( line.startsWith( "dim" ) ) {  // dimensionality parsing
						final String[] entries = line.split("=" );
						if ( entries.length != 2 ) {
							Log.error(pfx + lineNo + " does not look like [ dim = n ]: " + line);
							return null;						
						}
						
						try {
							dim = Integer.parseInt( entries[1].trim() );
						}
						catch (final NumberFormatException e) {
							Log.error(pfx + lineNo + ": Cannot parse dimensionality: " + entries[1].trim());
							return null;														
						}

					} else if ( line.startsWith( "multiseries" ) )  {
						final String[] entries = line.split("=" );
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
						final String[] entries = line.split(";");
						if (entries.length != 3) {
							Log.error(pfx + lineNo + " does not have 3 entries! [fileName; seriesNr; (x,y,...)]");
							return null;						
						}

						final String imageName = entries[0].trim();
						if (imageName.isEmpty()) {
							Log.error(pfx + lineNo + ": You have to give a filename [fileName; ; (x,y,...)]: " + line);
							return null;						
						}
						
						final int seriesNr;
						if (multiSeries) {
							final String imageSeries = entries[1].trim();  // sub-volume (series nr)
							if (imageSeries.isEmpty()) {
								Log.info(pfx + lineNo + ": Series index required [fileName; series; (x,y,...)" );
							} else {
								try {
									seriesNr = Integer.parseInt( imageSeries );
									Log.info(pfx + lineNo + ": Series nr (sub-volume): " + seriesNr);
								}
								catch (final NumberFormatException e) {
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
						final String[] points = point.split(",");
						if (points.length != dim) {
							Log.error(pfx + lineNo + ": Wrong format of coordinates: (x,y,z,...), dim = " + dim + ": " + point);
							return null;
						}
						final float[] offset = new float[ dim ];
						for ( int i = 0; i < dim; i++ ) {
							try {
								offset[ i ] = Float.parseFloat( points[i].trim() ); 
							}
							catch (final NumberFormatException e) {
								Log.error(pfx + lineNo + ": Cannot parse number: " + points[i].trim());
								return null;							
							}
						}
						
						// now we can assemble the ImageCollectionElement:
						final ImageCollectionElement element = new ImageCollectionElement(
								new File( directory, imageName ), index++ );
						element.setDimensionality( dim );
						if ( dim == 3 )
							element.setModel( new TranslationModel3D() );
						else
							element.setModel( new TranslationModel2D() );
						element.setOffset( offset );

						if (multiSeries) {
							throw new RuntimeException("not supported");
						}

						elements.add( element );
					}
				}
			}
		} catch (final IOException e) {
			Log.error( "Stitching_Grid.getLayoutFromFile: " + e );
			return null;
		}

		return elements;
	}

	// from: register_virtual_stack_slices/src/main/java/register_virtual_stack/Transform_Virtual_Stack_MT.java
	/**
	 * Read coordinate transform from file (generated in Register_Virtual_Stack)
	 *
	 * @param filename Complete file name (including path)
	 * @return The list of coordinate transforms
	 */
	public static CoordinateTransformList<CoordinateTransform> readCoordinateTransform(final String filename)
	{
		final CoordinateTransformList<CoordinateTransform> ctl = new CoordinateTransformList<>();
		try (final Stream<String> lines = Files.lines(Path.of(filename))) {
			lines.forEach(line -> {
				int index;
				if ((index = line.indexOf("class=")) != -1) {
					// skip "class"
					index += 5;

					// read coordinate transform class name
					final int index2 = line.indexOf("\"", index + 2);
					final String ct_class = line.substring(index + 2, index2);
					final CoordinateTransform ct;
					try {
						ct = (CoordinateTransform) Class.forName(ct_class).getDeclaredConstructor().newInstance();
					} catch (final Exception e) {
						throw new RuntimeException(e);
					}

					// read coordinate transform info
					final int index3 = line.indexOf("=", index2 + 1);
					final int index4 = line.indexOf("\"", index3 + 2);
					final String data = line.substring(index3 + 2, index4);
					ct.init(data);
					ctl.add(ct);
				}
			});
		} catch (final IOException e) {
			throw new RuntimeException(e);
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
		final TransformMesh mesh = new TransformMesh(transform, 32, width, height);
		return mesh.getBoundingBox();
	}
}
