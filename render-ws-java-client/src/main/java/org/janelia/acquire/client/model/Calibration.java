package org.janelia.acquire.client.model;

import org.janelia.alignment.spec.ListTransformSpec;

/**
 * Image Catcher data for a camera calibration (lens correction transform list).
 *
 * @author Eric Trautman
 */
@SuppressWarnings("ALL")
public class Calibration {

    private String name;
    private ListTransformSpec content;

    public Calibration() {
    }

    public ListTransformSpec getContent() {
        return content;
    }

}
