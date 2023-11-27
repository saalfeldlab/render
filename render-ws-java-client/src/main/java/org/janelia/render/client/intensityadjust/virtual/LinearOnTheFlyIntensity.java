package org.janelia.render.client.intensityadjust.virtual;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.intensity.LinearIntensityMap;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.VisualizeTools;

import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;

public class LinearOnTheFlyIntensity extends OnTheFlyIntensity
{
	public LinearOnTheFlyIntensity(
			final TileSpec p,
			final double[][] ab_coefficients,
			final int numCoefficients )
	{
		super(p, ab_coefficients, numCoefficients);
	}

	@Override
	public FloatProcessor computeIntensityCorrectionOnTheFly(
			final TileSpec p,
			final double[][] ab_coefficients, // all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
			final int numCoefficients, // e.g. if numCoefficients==4, then we have 16 tiles per image
			final ImageProcessorCache imageProcessorCache )
	{
		//final ArrayList< Tile< ? extends M > > tiles = coefficientsTiles.get( p );

		final FloatProcessor as = new FloatProcessor( numCoefficients, numCoefficients );
		final FloatProcessor bs = new FloatProcessor( numCoefficients, numCoefficients );

		final ImageProcessorWithMasks imp = VisualizeTools.getUntransformedProcessorWithMasks(p, imageProcessorCache);

		final FloatProcessor fp = imp.ip.convertToFloatProcessor();
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

		final LinearIntensityMap<FloatType> map =
				new LinearIntensityMap<FloatType>(
						ImagePlusImgs.from( new ImagePlus( "", coefficientsStack ) ));

		final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
		final Img< FloatType > img = ArrayImgs.floats((float[])fp.getPixels(), dims);

		map.run(img);

		//new ImagePlus( "imp.ip", imp.ip ).show();
		//new ImagePlus( "fp", fp ).show();
		//SimpleMultiThreading.threadHaltUnClean();

		return fp;
	}

	@Override
	public IntensityMap8BitFilter toFilter() {
		return new LinearIntensityMap8BitFilter(numCoefficients,
												numCoefficients,
												2,
												coefficients);
	}
}
