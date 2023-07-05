package org.janelia.alignment.match.cache;

import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasIdWithRenderContext;

/**
 * Supports use of different loaders for different (named) stages in a multi-stage match process.
 *
 * @author Eric Trautman
 */
public class MultiStageCanvasDataLoader
        extends CanvasDataLoader {

    private final Map<String, CanvasDataLoader> nameToLoaderMap;

    /**
     * @param  nameToLoaderMap  map of loaders to use for each stage.
     */
    public MultiStageCanvasDataLoader(final Map<String, CanvasDataLoader> nameToLoaderMap) {
        super(buildDataLoaderId(nameToLoaderMap));
        this.nameToLoaderMap = nameToLoaderMap;
    }

    /**
     * @return an identifier for loader with these parameters to distinguish it from other loaders.
     */
    public static String buildDataLoaderId(final Map<String, CanvasDataLoader> nameToLoaderMap) {
        final StringBuilder sb = new StringBuilder();
        for (final String name : nameToLoaderMap.keySet().stream().sorted().collect(Collectors.toList())) {
            sb.append(name);
            sb.append("::");
            sb.append(nameToLoaderMap.get(name).getDataLoaderId().hashCode());
            sb.append("|");
        }
        return sb.toString();
    }

    public int getNumberOfStages() {
        return nameToLoaderMap.size();
    }

    @Override
    public CachedCanvasData load(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws Exception {

        final String loaderName = canvasIdWithRenderContext.getLoaderName();
        final CanvasDataLoader dataLoader = nameToLoaderMap.get(loaderName);

        if (dataLoader == null) {
            throw new IllegalStateException(
                    "missing loader with name '" + loaderName + "' for " + canvasIdWithRenderContext);
        }

        return dataLoader.load(canvasIdWithRenderContext);
    }

}
