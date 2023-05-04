package org.janelia.alignment.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;

/**
 * Utility methods for working with Neuroglancer.
 *
 * @author Eric Trautman
 */
public class NeuroglancerUtil {

    /**
     * @param  rendererUrl    URL for neuroglancer and render web services host
     *                        ( e.g. http://renderer.int.janelia.org:8080 )
     * @param  stackMetaData  metadata for stack
     *
     * @return 2D neuroglancer URL for specified stack.
     */
    public static String buildRenderStackUrlString(final String rendererUrl, //
                                                   final StackMetaData stackMetaData) {

        final List<Double> res = stackMetaData.getCurrentResolutionValues();
        final String stackDimensions = "\"x\":[" + res.get(0).intValue() + "e-9,\"m\"]," +
                                       "\"y\":[" + res.get(1).intValue() + "e-9,\"m\"]," +
                                       "\"z\":[1e-8,\"m\"]"; // needs to be hardcoded for 2D view

        final Bounds bounds = stackMetaData.getStats().getStackBounds();
        final String position = (int) bounds.getCenterX() + "," +
                                (int) bounds.getCenterY() + "," +
                                bounds.getMinZ().intValue();

        final StackId stackId = stackMetaData.getStackId();

        final String ngJson =
                "{\"dimensions\":{" + stackDimensions + "}," +
                "\"position\":[" + position + "],\"crossSectionScale\":16,\"projectionScale\":32768," +
                "\"layers\":[{\"type\":\"image\",\"source\":{\"url\":\"render://" +
                rendererUrl + "/" + stackId.getOwner() + "/" + stackId.getProject() + "/" + stackId.getStack() +
                "\",\"subsources\":{\"default\":true,\"bounds\":true},\"enableDefaultSubsources\":false}," +
                "\"tab\":\"source\",\"name\":\"" + stackId.getStack() + "\"}]," +
                "\"selectedLayer\":{\"layer\":\"" + stackId.getStack() + "\"},\"layout\":\"xy\"}";

        return rendererUrl + "/ng/#!" + URLEncoder.encode(ngJson, StandardCharsets.UTF_8);
    }

    /**
     * @param  args  owner project stack (optional serviceHostAndPort)
     */
    public static void main(final String[] args) {

        final String[] effectiveArgs = args.length > 2 ? args : new String[] {
            "hess",
            "wafer_52_cut_00030_to_00039",
            "slab_033_all_align_t2_ic"
        };

        final String owner = effectiveArgs[0];
        final String project = effectiveArgs[1];
        final String stack = effectiveArgs[2];
        final String serviceHostAndPort = effectiveArgs.length > 3 ?
                                          effectiveArgs[3] : "http://renderer.int.janelia.org:8080";

        final String stackUrl = serviceHostAndPort + "/render-ws/v1/owner/" + owner + "/project/" + project + "/stack/" + stack;
        final StackMetaData stackMetaData = StackMetaData.loadFromUrl(stackUrl);

        System.out.println("Neuroglancer URL is:");
        System.out.println(buildRenderStackUrlString(serviceHostAndPort,
                                                     stackMetaData));
    }
}
