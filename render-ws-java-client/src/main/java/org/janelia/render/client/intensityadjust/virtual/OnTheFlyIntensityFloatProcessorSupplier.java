package org.janelia.render.client.intensityadjust.virtual;

import java.util.function.Supplier;

import org.janelia.alignment.util.ImageProcessorCache;

import ij.process.FloatProcessor;

public class OnTheFlyIntensityFloatProcessorSupplier extends OnTheFlyIntensitySupplier implements Supplier<FloatProcessor>
{
	public OnTheFlyIntensityFloatProcessorSupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
	{
		super(otfi, imageProcessorCache);
	}

	@Override
	public FloatProcessor get() { return otfi.computeIntensityCorrectionOnTheFly(imageProcessorCache); }
}
