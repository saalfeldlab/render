package org.janelia.alignment.match.cache;

import java.util.HashMap;
import java.util.Map;

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
     * @param  dataClass    class of data loader used for all stages (each stage will have a different instance).
     */
    public MultiStageCanvasDataLoader(final Class<? extends CachedCanvasData> dataClass) {
        super(dataClass);
        this.nameToLoaderMap = new HashMap<>();
    }

    public void addLoader(final String name,
                          final CanvasDataLoader dataLoader) {
        nameToLoaderMap.put(name, dataLoader);
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
