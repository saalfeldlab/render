package org.janelia.render.client.tile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.RenderWebServiceParameters;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.fft.FourierTransform;
import net.imglib2.algorithm.fft.InverseFourierTransform;
import net.imglib2.algorithm.fft2.FFTConvolution;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Utility to render tiles locally for debugging issues.
 *
 * @author Eric Trautman
 */
public class RenderTilesTest {

	public static < T extends RealType<T> & NativeType<T>> Img< T > fftBandpasscorrection( final RandomAccessibleInterval<T> input, final int numThreads )
	{
		FourierTransform<T, ComplexFloatType > fft = new FourierTransform<T, ComplexFloatType>( input, new ArrayImgFactory<ComplexFloatType>( new ComplexFloatType() ),new ComplexFloatType() );
		fft.process();
		fft.setNumThreads( numThreads );
		final Img<ComplexFloatType> fftImg = fft.getResult();

		System.out.println( Util.printInterval( fftImg ) );
		//ImageJFunctions.show( fftImg ).setTitle( "fft" );

		applyMask( fftImg, createMask( fftImg ) );

		//ImageJFunctions.show( fftImg ).setTitle( "fft bandpass" );

		final InverseFourierTransform< T, ComplexFloatType > ifft = new InverseFourierTransform<>( fftImg, fft );
		ifft.process();
		ifft.setNumThreads( numThreads );
		final Img< T > templateInverse = ifft.getResult();

		//ImageJFunctions.show( templateInverse ).setTitle("ifft");

		return templateInverse;
		
		//Gauss3.gauss( 500, Views.extendMirrorDouble( templateInverse ), templateInverse );
		//ImageJFunctions.show( templateInverse ).setTitle("ifft bg");

		// TODO: get pattern along X and remove

		//check: FFTConvolution<RealType<R>>

		/*
		final T type = Util.getTypeFromInterval( input );
		final RandomAccessibleInterval<T> img = Views.zeroMin( input );
		final RandomAccessibleInterval<T> out = new ArrayImgFactory<T>( type ).create( img.dimensionsAsLongArray() );
		final Img<ComplexFloatType> fft = FFT.realToComplex(img, new ArrayImgFactory<>( new ComplexFloatType() ) );
		ImageJFunctions.show( fft );
		FFT.complexToReal( fft, out );
		ImageJFunctions.show( out );
		*/
	}

	final static int extraY = 6;

	private static <T extends RealType<T>> void clear( final RandomAccessibleInterval<T> img, final int x, final int y, final int w, final int h)
	{
		final RandomAccess< T > r = img.randomAccess();

		for ( int y1 = y-extraY; y1 < y+h+extraY; ++y1 )
			for ( int x1 = x; x1 < x+w; ++x1 )
			{
				r.setPosition(x1, 0);
				r.setPosition(y1, 1);
				r.get().setZero();
			}
	}

	public static Img<FloatType> createMask( final Dimensions dim )
	{
		if ( dim.dimension( 0 ) != 6161 || dim.dimension( 1 ) != 10920 )
			throw new RuntimeException( "this mask is hard-coded for an FFT size of 6161x10920.");

		final ArrayImg<FloatType, FloatArray> mask = ArrayImgs.floats( dim.dimensionsAsLongArray() );

		for ( final FloatType t : mask )
			t.setOne();

		//makeRectangle(6043, 5459, 113, 3);
		clear(mask, 6043, 5459, 113, 3);

		//makeRectangle(5960, 5458, 83, 5);
		clear(mask, 5960, 5458, 83, 5);

		//makeRectangle(5798, 5456, 162, 9);
		clear(mask, 5798, 5456, 162, 9);

		//makeRectangle(5573, 5453, 225, 15);
		clear(mask, 5573, 5453, 225, 15);

		//makeRectangle(0, 5448, 5573, 25);
		clear(mask, 0, 5448, 5573, 25);

		// top stripe
		//makeRectangle(0, 5360, 4569, 5);
		clear(mask, 0, 5360, 4569, 5);

		// bot stripe
		//makeRectangle(0, 5556, 4569, 5);
		clear(mask, 0, 5556, 4569, 5);

		// does not work, image gets darker
		//Gauss3.gauss(1, Views.extendPeriodic( mask ), mask );

		return mask;
	}

	public static void applyMask( final RandomAccessibleInterval<ComplexFloatType> fft, final RandomAccessibleInterval<FloatType> mask )
	{
		final Cursor<ComplexFloatType> cFFT = Views.flatIterable( fft ).cursor();
		final Cursor<FloatType> cM = Views.flatIterable( mask ).cursor();

		while (cM.hasNext() )
		{
			cFFT.next().mul( cM.next().get() );
		}
	}

	public static Img<FloatType> createPattern( final long[] dimensions, final double avgIntensity, final int numThreads )
	{
		final Img<FloatType> pattern = ArrayImgs.floats( dimensions );

    	for ( final FloatType t : pattern )
    		t.set( 1 );

    	final Img<FloatType> patternFiltered = fftBandpasscorrection( pattern, numThreads ); // this could be loaded from disc, always the same

    	for ( final FloatType t : patternFiltered )
    		t.set( (float)(t.get() * avgIntensity - avgIntensity) );

    	return patternFiltered;
	}

	public static < T extends RealType<T>> double avgIntensity( final RandomAccessibleInterval< T > img )
	{
		final long numPx = Views.iterable(img).size();
		final RealSum s = new RealSum( (int)numPx );

		for ( final T type : Views.iterable(img) )
			s.add( type.getRealDouble() );

		return s.getSum() / (double)numPx;
	}

    public static void main(final String[] args) {

    	final int numThreads = 8;
    	new ImageJ();
    	final ImagePlus imp = new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/FIB-SEM/22-08-23_114401_0-0-2.28132-crop.0.tif" );
    	final Img<UnsignedByteType> img = ImageJFunctions.wrapByte( imp );

    	//ImageJFunctions.show( img ).setTitle( "input" );
    	final double avg = avgIntensity( img );
    	System.out.println( avg );

    	final Img<UnsignedByteType> imgCorr = fftBandpasscorrection( img, numThreads );
    	final Img<FloatType> patternCorr = createPattern(imgCorr.dimensionsAsLongArray(), avg, numThreads);
    	final RandomAccessibleInterval<UnsignedByteType> fixed = Converters.convertRAI(imgCorr, patternCorr, (i1,i2,o) -> { o.set( Math.max( 0, Math.min( 255, Math.round( i1.get() - i2.get() ) ) ) ); }, new UnsignedByteType() );

    	ImageJFunctions.show( imgCorr ).setTitle( "imgCorr" );
    	ImageJFunctions.show( patternCorr ).setTitle( "patternCorr" );
    	ImageJFunctions.show( fixed ).setTitle( "fixed" );

    	SimpleMultiThreading.threadHaltUnClean();

        try {
            final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();

            // TODO: mount /nrs/fibsem to access align h5 data

            parameters.renderWeb = new RenderWebServiceParameters();
            parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
            parameters.renderWeb.owner = "fibsem";
            parameters.renderWeb.project = "Z0422_17_VNC_1";
            parameters.stack = "v4_acquire_trimmed_align";

            // TODO: set z bounds, row, and column for tiles of interest
            //   see http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B26771.75%2C9907.5%2C28131.9765625%5D%2C%22crossSectionScale%22:128%2C%22projectionScale%22:65536%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%22n5://http://renderer.int.janelia.org:8080/n5_sources/fibsem/Z0422_17_VNC_1.n5/render/Z0422_17_VNC_1/v4_acquire_trimmed_align___20221108_150533%22%2C%22tab%22:%22source%22%2C%22name%22:%22Z0422_17_VNC_1%20v4_acquire_trimmed_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22Z0422_17_VNC_1%20v4_acquire_trimmed_align%22%7D%2C%22layout%22:%224panel%22%7D
            final Double minZ = 28131.0;
            final Double maxZ = 28131.0;
            final int row = 0;
            final int column = 2;

            // TODO: set to true to include scan correction, set to false for completely raw tile
            final boolean includeScanCorrectionTransforms = false;

            // TODO: downscale if you like
            final double renderScale = 1.0;

            // TODO: set this to existing directory instead of null to save tiles rather than view them interactively
            final File savedTileDirectory = null; // new File("/Users/preibischs/Desktop");

            // TODO: change to another format if you are saving files and don't want pngs
            parameters.format = Utils.PNG_FORMAT;

            //
            // For Eric
            // TODO: there are masks applied here, we do not want that, we want the raw-raw image
            //
            //
            renderTiles(parameters,
                        minZ,
                        maxZ,
                        row,
                        column,
                        includeScanCorrectionTransforms,
                        renderScale,
                        savedTileDirectory);

        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    // you should not need to change anything in here ...
    private static void renderTiles(final RenderTileWithTransformsClient.Parameters parameters,
                                    final Double minZ,
                                    final Double maxZ,
                                    final int row,
                                    final int column,
                                    final boolean includeScanCorrectionTransforms,
                                    final double renderScale,
                                    final File savedTileDirectory)
            throws IOException {

        final RenderDataClient dataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                 parameters.renderWeb.owner,
                                                                 parameters.renderWeb.project);

        final ResolvedTileSpecCollection resolvedTileSpecs = dataClient.getResolvedTiles(parameters.stack,
                                                                                         minZ,
                                                                                         maxZ,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null);
        resolvedTileSpecs.resolveTileSpecs();

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        if (savedTileDirectory == null) {
            System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
            new ImageJ();
        }

        for (final TileSpec tileSpec : resolvedTileSpecs.getTileSpecs()) {

            final String tileId = tileSpec.getTileId();

            final Matcher m = TILE_ID_PATTERN.matcher(tileId);
            if (m.matches()) {
                final int tileRow = Integer.parseInt(m.group(1));
                final int tileColumn = Integer.parseInt(m.group(2));

                if ((row == tileRow) && (column == tileColumn)) {
                    final List<TransformSpec> transformSpecs =
                            includeScanCorrectionTransforms ?
                            tileSpec.getTransforms().getMatchSpecList().toUtilList() : new ArrayList<>();

                    final File savedTileFile =
                            savedTileDirectory == null ? null : new File(savedTileDirectory,
                                                                         tileId + "." + parameters.format);
                    final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                            client.renderTile(tileId,
                                              transformSpecs,
                                              renderScale,
                                              null,
                                              savedTileFile);

                    if (savedTileDirectory == null) {
                        new ImagePlus(tileId, ipwm.ip).show();
                    }
                }
            }
        }
    }

    private static final Pattern TILE_ID_PATTERN = Pattern.compile(".*_0-(\\d)-(\\d)\\.(?:patch\\.)?(\\d++)\\.0");

}
