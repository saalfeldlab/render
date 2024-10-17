package org.janelia.render.client.spark.destreak;

import ij.ImagePlus;
import org.janelia.alignment.destreak.StreakFinder;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;

import java.io.IOException;

public class StreakInfoTemp {
	public static void main(final String[] args) throws IOException {
		final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
		final String owner = "fibsem";
		final String project = "jrc_pri_neuron_0710Dish4";
		final String stack = "v2_acquire_align";

		final int meanFilterSize = 100;
		final double threshold = 8.0;
		final int blurRadius = 0;

		final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, owner, project);
		final StackMetaData metaData = renderDataClient.getStackMetaData(stack);

		final Bounds bounds = metaData.getStackBounds();
		System.out.println(" +++ Bounds: [" + bounds.getMinX() + ", " + bounds.getMinY() + ", " + bounds.getMinZ() + "] -> [" + bounds.getMaxX() + ", " + bounds.getMaxY() + ", " + bounds.getMaxZ() + "]");
		System.out.println(" +++ Cell size: " + bounds.getWidth() + " x " + bounds.getHeight() + " -> " + (bounds.getWidth() / 1000 + 1) + "x" + (bounds.getHeight() / 500 + 1));
		System.out.println(" +++ z-layers: " + (bounds.getMaxZ() - bounds.getMinZ() + 1));

		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_pri-neuro_0710dish-4/z7350-0-0-0.png";
		final ImagePlus imp = new ImagePlus(srcPath);
		final StreakFinder finder = new StreakFinder(meanFilterSize, threshold, blurRadius);
		final ImagePlus mask = finder.createStreakMask(imp);

		new ij.ImageJ();
		imp.show();
		mask.show();
	}
}
