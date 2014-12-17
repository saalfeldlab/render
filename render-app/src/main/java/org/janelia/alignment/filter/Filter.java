package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

public interface Filter {
    public ImageProcessor process(ImageProcessor ip, final double scale);
}
