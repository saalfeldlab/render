package org.janelia.render.client.intensityadjust.virtual;

import java.util.function.Supplier;

import org.janelia.alignment.util.ImageProcessorCache;

import ij.process.ByteProcessor;

public class OnTheFlyIntensityByteProcessorSupplier extends OnTheFlyIntensitySupplier implements Supplier<ByteProcessor>
{
	public OnTheFlyIntensityByteProcessorSupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
	{
		super(otfi, imageProcessorCache);
	}

	@Override
	public ByteProcessor get() { return otfi.computeIntensityCorrection8BitOnTheFly(imageProcessorCache); }
}
