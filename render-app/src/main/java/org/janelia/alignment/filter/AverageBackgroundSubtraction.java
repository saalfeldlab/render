package org.janelia.alignment.filter;

import ij.process.ImageProcessor;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.util.ImageProcessorCache;

import java.util.HashMap;
import java.util.Map;

public class AverageBackgroundSubtraction implements Filter {
	private static final ImageProcessorCache cache =
			new ImageProcessorCache(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS, false, false);

	private String backgroundPath;
	private double backgroundScale;
	private float offset;

	// empty constructor required to create instances from specifications
	@SuppressWarnings("unused")
	public AverageBackgroundSubtraction() {
		this(null, 1.0, 0.0f);
	}

	public AverageBackgroundSubtraction(final String backgroundPath, final double backgroundScale, final float offset) {
		this.backgroundPath = backgroundPath;
		this.backgroundScale = backgroundScale;
		this.offset = offset;
	}

	@Override
	public void init(final Map<String, String> params) {
		this.backgroundPath = Filter.getStringParameter("path", params);
		this.backgroundScale = Filter.getDoubleParameter("scale", params);
		this.offset = Filter.getFloatParameter("offset", params);
	}

	@Override
	public Map<String, String> toParametersMap() {
		final Map<String, String> map = new HashMap<>();
		map.put("path", backgroundPath);
		map.put("scale", String.valueOf(backgroundScale));
		map.put("offset", String.valueOf(offset));
		return map;
	}

	@Override
	public void process(final ImageProcessor ip, final double scale) {
		final ImageProcessor background = cache.get(backgroundPath, 0, false, false, ImageLoader.LoaderType.IMAGEJ_DEFAULT, null);



	}
}
