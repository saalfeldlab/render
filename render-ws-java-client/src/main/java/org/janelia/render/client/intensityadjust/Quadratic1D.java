package org.janelia.render.client.intensityadjust;

import mpicbg.models.CoordinateTransform;

public interface Quadratic1D<T extends Quadratic1D<T>> extends CoordinateTransform {
	void toArray(double[] var1);
}
