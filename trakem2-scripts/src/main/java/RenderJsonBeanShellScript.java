import ij.*;
import ini.trakem2.display.*;
import mpicbg.trakem2.transform.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for generating JSON formatted Render Web Service tile data from TrakEM2 beanshell scripts.
 *
 * These were adapted from Stephan Saalfeld's original
 * <a href="https://github.com/saalfeldlab/render/blob/5041ddbff9784f6a2c542b2afafca7f36ef4e745/src/main/other/export-tile-specs.bsh">
 *     export-tile-specs.bsh
 * </a> script.
 *
 * I've set this up as a java class in a maven module with TrakEM2 dependencies to allow for editing within an IDE.
 * It obviously makes the script more verbose, but is useful when you are not familiar with the TrakEM2 models.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"unchecked", "UnusedDeclaration"})
public class RenderJsonBeanShellScript extends BeanShellScript {

    public void flattenTransforms(CoordinateTransform ct, List flattenedList) {
        if (CoordinateTransformList.class.isInstance(ct)) {
            CoordinateTransformList<CoordinateTransform> ctList = (CoordinateTransformList<CoordinateTransform>) ct;
            for (CoordinateTransform ctListItem : ctList.getList(null)) {
                flattenTransforms(ctListItem, flattenedList);
            }
        } else {
            flattenedList.add(ct);
        }
    }

    public boolean appendIfNotFirst(boolean isFirst,
                                    String text,
                                    StringBuilder b) {
        if (! isFirst) {
            b.append(text);
        }
        return false;
    }

    public void appendTransformsJson(List<CoordinateTransform> transforms,
                                     StringBuilder b) {

        b.append("  \"transforms\": {\n");
        b.append("    \"type\": \"list\",\n");
        b.append("    \"specList\": [\n");

        boolean isFirst = true;
        for (CoordinateTransform t : transforms) {
            isFirst = appendIfNotFirst(isFirst, ",\n", b);
            b.append("      {\n");
            b.append("        \"className\": \"").append(t.getClass().getCanonicalName()).append("\",\n");
            b.append("        \"dataString\": \"").append(t.toDataString()).append("\"\n");
            b.append("      }");
        }

        b.append("\n    ]");
        b.append("\n  }");
    }

    public void appendPatchJson(Patch patch,
                                double z,
                                StringBuilder b) {

        CoordinateTransform ct = patch.getFullCoordinateTransform();

        List transforms = new ArrayList<CoordinateTransform>();
        flattenTransforms(ct, transforms);

        TransformMesh m = new TransformMesh(ct, patch.getMeshResolution(), patch.getOWidth(), patch.getOHeight());
        Rectangle box = m.getBoundingBox();

        b.append("{\n");
        b.append("  \"tileId\": \"").append(patch.getUniqueIdentifier()).append("\",\n");
        b.append("  \"z\": ").append(z).append("\",\n");
        b.append("  \"minX\": ").append(box.getX()).append(",\n");
        b.append("  \"minY\": ").append(box.getY()).append(",\n");
        b.append("  \"maxX\": ").append(box.getMaxX()).append(",\n");
        b.append("  \"maxY\": ").append(box.getMaxY()).append(",\n");
        b.append("  \"width\": ").append(patch.getOWidth()).append(",\n");
        b.append("  \"height\": ").append(patch.getOHeight()).append(",\n");
        b.append("  \"minIntensity\": ").append(patch.getMin()).append(",\n");
        b.append("  \"maxIntensity\": ").append(patch.getMax()).append(",\n");
        b.append("  \"mipmapLevels\": {\n");
        b.append("    \"0\": {\n");
        b.append("      \"imageUrl\": \"file:").append(patch.getFilePath()).append("\",\n");
        b.append("      \"maskUrl\": \"file:").append(patch.getAlphaMaskFilePath()).append("\"\n");
        b.append("    }\n");
        b.append("  },\n");

        appendTransformsJson(transforms, b);

        b.append("\n}");
    }

    public String layerToJson(Layer layer) {
        StringBuilder json = new StringBuilder(2048);
        boolean isFirst = true;
        for (Displayable p : layer.getDisplayables(Patch.class)) {
            isFirst = appendIfNotFirst(isFirst, ",\n", json);
            appendPatchJson((Patch) p, layer.getZ(), json);
        }
        return json.toString();
    }

    public void logLayerSetJson(LayerSet layerSet) {
        boolean isFirst = true;
        for (Layer layer : layerSet.getLayers()) {
            if (isFirst) {
                isFirst = false;
                IJ.log("[");
            } else {
                IJ.log(",");
            }
            IJ.log(layerToJson(layer));
        }
        IJ.log("]");
    }

    public void logCurrentLayer() {
        IJ.log(layerToJson(Display.getFront().getLayer()));
    }

    public void logCurrentLayerSet() {
        logLayerSetJson(Display.getFront().getLayerSet());
        print("Done");
    }

}
