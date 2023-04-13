package org.janelia.render.client.intensityadjust.virtual;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.alignment.intensity.LinearIntensityMap;
import org.janelia.render.client.solver.visualize.VisualizeTools;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.FloatImagePlus;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

public class OnTheFlyIntensity
{
	final MinimalTileSpecWrapper p;

	// all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
	// ArrayList< Tile< ? extends Affine1D< ? > > > subRegionTiles;
	// we only store the actual coefficients
	// contains [numCoefficients * numCoefficients][ab]
	final double[][] ab_coefficients;

	// e.g. if numCoefficients==4, then we have 16 tiles per image
	final int numCoefficients;

	public OnTheFlyIntensity(
			final MinimalTileSpecWrapper p,
			final double[][] ab_coefficients,
			final int numCoefficients )
	{
		this.p = p;
		this.ab_coefficients = ab_coefficients;
		this.numCoefficients = numCoefficients;
	}

	public MinimalTileSpecWrapper getMinimalTileSpecWrapper() { return p; }
	public double[][] getCoefficients() { return ab_coefficients; }
	public int getNumCoefficients() { return numCoefficients; }

	public FloatProcessor computeIntensityCorrectionOnTheFly( final ImageProcessorCache imageProcessorCache )
	{
		return computeIntensityCorrectionOnTheFly( p, ab_coefficients, numCoefficients, imageProcessorCache);
	}

	public ByteProcessor computeIntensityCorrection8BitOnTheFly( final ImageProcessorCache imageProcessorCache )
	{
		final FloatProcessor correctedSource = computeIntensityCorrectionOnTheFly(imageProcessorCache);
		
		// Need to reset intensity range back to full 8-bit before converting to byte processor!
		correctedSource.setMinAndMax(0, 255);
		final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();

		return correctedSource8Bit;
	}

	public static /*Pair<ByteProcessor, */FloatProcessor/*>*/ computeIntensityCorrectionOnTheFly(
			final MinimalTileSpecWrapper p,
			final double[][] ab_coefficients, // all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
			final int numCoefficients, // e.g. if numCoefficients==4, then we have 16 tiles per image
			final ImageProcessorCache imageProcessorCache )
	{
		//final ArrayList< Tile< ? extends M > > tiles = coefficientsTiles.get( p );

		final FloatProcessor as = new FloatProcessor( numCoefficients, numCoefficients );
		final FloatProcessor bs = new FloatProcessor( numCoefficients, numCoefficients );

		final ImageProcessorWithMasks imp = VisualizeTools.getUntransformedProcessorWithMasks(p.getTileSpec(),
																							  imageProcessorCache);

		FloatProcessor fp = imp.ip.convertToFloatProcessor();
		fp.resetMinAndMax();
		final double min = 0;//fp.getMin();//patch.getMin();
		final double max = 255;//fp.getMax();//patch.getMax();
		System.out.println( min + ", " + max );

		for ( int i = 0; i < numCoefficients * numCoefficients; ++i )
		{
			/*
			final Tile< ? extends Affine1D< ? > > t = tiles.get( i );
			final Affine1D< ? > affine = t.getModel();
			affine.toArray( ab );
			*/

			final double[] ab = ab_coefficients[ i ];

			/* coefficients mapping into existing [min, max] */
			as.setf( i, ( float ) ab[ 0 ] );
			bs.setf( i, ( float ) ( ( max - min ) * ab[ 1 ] + min - ab[ 0 ] * min ) );
			// TODO: what to do if it's a quadratic function?
		}
		final ImageStack coefficientsStack = new ImageStack( numCoefficients, numCoefficients );
		coefficientsStack.addSlice( as );
		coefficientsStack.addSlice( bs );

		//new ImagePlus( "a", as ).show();
		//new ImagePlus( "b", bs ).show();
		//SimpleMultiThreading.threadHaltUnClean();

		//final String itsPath = itsDir + FSLoader.createIdPath( Long.toString( p.getId() ), "it", ".tif" );
		//new File( itsPath ).getParentFile().mkdirs();
		//IJ.saveAs( new ImagePlus( "", coefficientsStack ), "tif", itsPath );

		@SuppressWarnings({"rawtypes", "unchecked"})
		final LinearIntensityMap<FloatType> map =
				new LinearIntensityMap<FloatType>(
						(FloatImagePlus)ImagePlusImgs.from( new ImagePlus( "", coefficientsStack ) ));

		final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
		final Img< FloatType > img = ArrayImgs.floats((float[])fp.getPixels(), dims);

		map.run(img);

		//new ImagePlus( "imp.ip", imp.ip ).show();
		//new ImagePlus( "fp", fp ).show();
		//SimpleMultiThreading.threadHaltUnClean();

		return fp;
	}

}
