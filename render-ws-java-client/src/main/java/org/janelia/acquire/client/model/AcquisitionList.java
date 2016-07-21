package org.janelia.acquire.client.model;

import java.util.List;

/**
 * Image Catcher wrapper for list of acquisition data elements.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("ALL")
public class AcquisitionList {

    private List<Acquisition> acquisitions;

    public AcquisitionList() {
    }

    public List<Acquisition> getAcquisitions() {
        return acquisitions;
    }

}
