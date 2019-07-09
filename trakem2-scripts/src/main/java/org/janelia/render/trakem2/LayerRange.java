package org.janelia.render.trakem2;

import ij.gui.GenericDialog;

import java.util.List;

import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;

/**
 * Utility for identifying and validating a range of TrakEM2 layers.
 *
 * @author Eric Trautman
 */
class LayerRange {

    private double minZ;
    private double maxZ;

    private LayerSet layerSet;

    LayerRange(final double minZ,
               final double maxZ) {
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.setLayerSet();
    }

    void setLayerSet() {
        final Display front = Display.getFront();
        if (front == null) {
            final List<Project> projectList = Project.getProjects();
            final Project trakProject = projectList.get(0);
            layerSet = trakProject.getRootLayerSet();
        } else {
            layerSet = front.getLayerSet();
        }
    }

    List<Layer> getLayersInRange() {
        final Layer firstLayer = layerSet.getLayer(minZ);
        final Layer lastLayer = layerSet.getLayer(maxZ);
        return layerSet.getLayers(firstLayer, lastLayer);
    }

    void addFieldsToDialog(final GenericDialog dialog) {
        dialog.addNumericField("TrakEM2 Min Z", minZ, 1);
        dialog.addNumericField("TrakEM2 Max Z", maxZ, 1);
    }

    void setFieldsFromDialog(final GenericDialog dialog) {
        minZ = dialog.getNextNumber();
        maxZ = dialog.getNextNumber();
    }

    void validate()
            throws IllegalArgumentException {

        final Layer firstLayer = layerSet.getLayer(minZ);
        if (firstLayer == null) {
            throw new IllegalArgumentException("no TrakEM2 layers found with min Z " + minZ);
        }

        final Layer lastLayer = layerSet.getLayer(maxZ);
        if (lastLayer == null) {
            throw new IllegalArgumentException("no TrakEM2 layers found with max Z " + maxZ);
        }

    }

}
