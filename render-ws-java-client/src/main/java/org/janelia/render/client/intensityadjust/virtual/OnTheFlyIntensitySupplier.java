package org.janelia.render.client.intensityadjust.virtual;

import org.janelia.alignment.util.ImageProcessorCache;

public abstract class OnTheFlyIntensitySupplier
{
	final OnTheFlyIntensity otfi;
	final ImageProcessorCache imageProcessorCache;

	public OnTheFlyIntensitySupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
	{
		this.otfi = otfi;
		this.imageProcessorCache = imageProcessorCache;
	}
}