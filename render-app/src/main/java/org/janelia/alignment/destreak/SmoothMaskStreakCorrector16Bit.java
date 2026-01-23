package org.janelia.alignment.destreak;

import ij.process.ImageProcessor;
import org.janelia.alignment.filter.Filter;

/**
 * 16-bit version of {@link SmoothMaskStreakCorrector} that can also be used as {@link Filter}.
 *
 * @author Michael Innerberger
 */
public class SmoothMaskStreakCorrector16Bit extends SmoothMaskStreakCorrector {

	public SmoothMaskStreakCorrector16Bit() {
		super();
	}

	public SmoothMaskStreakCorrector16Bit(final int numThreads) {
		super(numThreads);
	}

	public SmoothMaskStreakCorrector16Bit(final int numThreads,
										  final int fftWidth,
										  final int fftHeight,
										  final int innerCutoff,
										  final int bandWidth,
										  final double angle) {
		super(numThreads, fftWidth, fftHeight, innerCutoff, bandWidth, angle);
	}

	public SmoothMaskStreakCorrector16Bit(final SmoothMaskStreakCorrector corrector) {
		super(corrector);
	}

	@Override
	public void process(final ImageProcessor ip, final double scale) {
		process16bit(ip, scale);
	}
}
