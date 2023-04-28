package org.janelia.render.client.intensityadjust.virtual;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;

public abstract class OnTheFlyIntensity {

	final MinimalTileSpecWrapper p;

	// all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
	// ArrayList< Tile< ? extends Affine1D< ? > > > subRegionTiles;
	// we only store the actual coefficients
	// contains [numCoefficients * numCoefficients][ab]
	final double[][] coefficients;

	// e.g. if numCoefficients==4, then we have 16 tiles per image
	final int numCoefficients;

	public OnTheFlyIntensity(
			final MinimalTileSpecWrapper p,
			final double[][] coefficients,
			final int numCoefficients )
	{
		this.p = p;
		this.coefficients = coefficients;
		this.numCoefficients = numCoefficients;
	}

	public MinimalTileSpecWrapper getMinimalTileSpecWrapper() { return p; }
	public double[][] getCoefficients() { return coefficients; }
	public int getNumCoefficients() { return numCoefficients; }

	public FloatProcessor computeIntensityCorrectionOnTheFly( final ImageProcessorCache imageProcessorCache )
	{
		return computeIntensityCorrectionOnTheFly( p, coefficients, numCoefficients, imageProcessorCache);
	}

	public ByteProcessor computeIntensityCorrection8BitOnTheFly( final ImageProcessorCache imageProcessorCache )
	{
		final FloatProcessor correctedSource = computeIntensityCorrectionOnTheFly(imageProcessorCache);

		// Need to reset intensity range back to full 8-bit before converting to byte processor!
		correctedSource.setMinAndMax(0, 255);
		final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();

		return correctedSource8Bit;
	}

	public abstract FloatProcessor computeIntensityCorrectionOnTheFly(
			final MinimalTileSpecWrapper p,
			final double[][] coefficients, // all coefficients needed for a single image (depends how its broken up initially), e.g., if each tile is a 1D affine, 2 numbers
			final int numCoefficients, // e.g. if numCoefficients==4, then we have 16 tiles per image
			final ImageProcessorCache imageProcessorCache);
}
