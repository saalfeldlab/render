package org.janelia.acquire.client.model;

import java.util.List;

/**
 * Image Catcher wrapper for list of calibration data elements.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("ALL")
public class CalibrationList {

    private List<Calibration> calibrations;

    public CalibrationList() {
    }

    public List<Calibration> getCalibrations() {
        return calibrations;
    }
}
