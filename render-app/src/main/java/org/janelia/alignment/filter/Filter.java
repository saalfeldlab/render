package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.io.Serializable;

public interface Filter extends Serializable {
    public ImageProcessor process(ImageProcessor ip, final double scale);
}
