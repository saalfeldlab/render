package org.janelia.render.client.solver.visualize;

import java.awt.image.BufferedImage;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.MinimalTileSpec;

public class RenderTools
{
	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackListFormat = ownerFormat + "/stacks";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String stackBoundsFormat = stackFormat  + "/bounds";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	final static private BufferedImage renderImage(
			final ImageProcessorCache ipCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final long x,
			final long y,
			final long z,
			final long w,
			final long h,
			final double scale,
			final boolean filter) {

		final String renderParametersUrlString = String.format(
				renderParametersFormat,
				baseUrl,
				owner,
				project,
				stack,
				z,
				x,
				y,
				w,
				h,
				scale);

		final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);
		renderParameters.setDoFilter(filter);
		final BufferedImage image = renderParameters.openTargetImage();
		ArgbRenderer.render(renderParameters, image, ipCache);

		return image;
	}

}
