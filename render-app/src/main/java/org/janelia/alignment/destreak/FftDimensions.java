package org.janelia.alignment.destreak;

import edu.mines.jtk.dsp.FftComplex;
import edu.mines.jtk.dsp.FftReal;


/**
 * A simple class for holding the width and height of the FFT of an image with given dimensions. The computations
 * of the size are based on the original code in net.imglib2.algorithm.fft.FourierTransform
 */
public class FftDimensions {
	public final int width;
	public final int height;

	private FftDimensions(final int width, final int height) {
		this.width = width;
		this.height = height;
	}

	/**
	 * Computes the FFT dimensions for an image of the given width and height.
	 *
	 * @param width  the width of the image
	 * @param height the height of the image
	 * @return The size of the FFT of the image as computed by net.imglib2.algorithm.fft.FourierTransform
	 */
	public static FftDimensions getFor(final int width, final int height) {
		final int extendedWidth = width + Math.max(Math.round(1.25f * width) - width, 12);
		final int extendedHeight = height + Math.max(Math.round(1.25f * height) - height, 12);
		final int fftWidth = FftReal.nfftFast(extendedWidth) / 2 + 1;
		final int fftHeight = FftComplex.nfftFast(extendedHeight);
		return new FftDimensions(fftWidth, fftHeight);
	}
}
