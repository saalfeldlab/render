package org.janelia.alignment.match;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import mpicbg.imagefeatures.Feature;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;

/**
 * List of features along with the rendering context used to produce them for a canvas.
 *
 * Includes {@link #writeToStorage(File, CanvasFeatureList)} and {@link #readFromStorage(File, CanvasId)} methods
 * to facilitate persistence to and retrieval from a file system.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureList implements Serializable {

    private final CanvasId canvasId;
    private final String renderParametersUrl;
    private final Double renderScale;
    private final Integer clipWidth;
    private final Integer clipHeight;
    private final List<Feature> featureList;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private CanvasFeatureList() {
        this(null, null, null, null, null, null);
    }

    public CanvasFeatureList(final CanvasId canvasId,
                             final String renderParametersUrl,
                             final Double renderScale,
                             final Integer clipWidth,
                             final Integer clipHeight,
                             final List<Feature> featureList) {
        this.canvasId = canvasId;
        this.renderParametersUrl = renderParametersUrl;
        this.renderScale = renderScale;
        this.clipWidth = clipWidth;
        this.clipHeight = clipHeight;
        this.featureList = featureList;
    }

    public CanvasId getCanvasId() {
        return canvasId;
    }

    public String getRenderParametersUrl() {
        return renderParametersUrl;
    }

    public double getRenderScale() {
        return renderScale;
    }

    public Integer getClipWidth() {
        return clipWidth;
    }

    public Integer getClipHeight() {
        return clipHeight;
    }

    public List<Feature> getFeatureList() {
        return featureList;
    }

    public int size() {
        return featureList.size();
    }

    /**
     * @param  rootDirectory  root directory for all features extracted in a particular run.
     * @param  canvasId       identifies the desired canvas.
     *
     * @return persisted feature list for the specified canvas.
     *
     * @throws IOException
     *   if the canvas feature storage file cannot be found or parsed.
     */
    public static CanvasFeatureList readFromStorage(final File rootDirectory,
                                                    final CanvasId canvasId)
            throws IOException {
        final Path path = getStoragePath(rootDirectory, canvasId);
        final Reader reader = new FileUtil().getExtensionBasedReader(path.toString());
        return JsonUtils.FAST_MAPPER.readValue(reader, CanvasFeatureList.class);
    }

    /**
     * Persists the specified feature data to disk (see {@link #getStoragePath(File, CanvasId)}).
     *
     * @param  rootDirectory      root directory for all features extracted in the current run.
     * @param  canvasFeatureList  feature data to persist.
     *
     * @throws IOException
     *   if the feature data cannot be persisted.
     */
    public static void writeToStorage(final File rootDirectory,
                                      final CanvasFeatureList canvasFeatureList)
            throws IOException {
        final Path path = getStoragePath(rootDirectory, canvasFeatureList.canvasId);
        FileUtil.ensureWritableDirectory(path.getParent().toFile());
        FileUtil.saveJsonFile(path.toString(), canvasFeatureList, JsonUtils.FAST_MAPPER);
    }

    /**
     * Builds a standard storage path for the specified canvas of the form:
     * [root]/[canvas_group_id]/[canvas_id].features.json.gz
     *
     * @param  rootDirectory  root directory for all features extracted in a particular run.
     * @param  canvasId       the current canvas.
     *
     * @return standard storage path for the specified canvas' feature data.
     */
    public static Path getStoragePath(final File rootDirectory,
                                      final CanvasId canvasId) {
        String name = canvasId.getId();
        if (canvasId.getRelativePosition() != null) {
            name = name + "__" + canvasId.getRelativePosition();
        }
        return Paths.get(rootDirectory.getAbsolutePath(),
                         canvasId.getGroupId(),
                         name + ".features.json.gz");
    }

}
