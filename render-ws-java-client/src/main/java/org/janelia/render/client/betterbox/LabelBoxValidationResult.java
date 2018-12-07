package org.janelia.render.client.betterbox;

import java.awt.Color;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.LabelImageProcessorCache;

/**
 * Rendered label box validation data.
 *
 * @author Eric Trautman
 */
public class LabelBoxValidationResult
        implements Serializable {

    private final Map<Double, LayerResult> zToLayerResult;

    /**
     * Constructs an empty result.
     */
    LabelBoxValidationResult() {

        this.zToLayerResult = new HashMap<>();
    }

    public Set<Double> getZValues() {
        return zToLayerResult.keySet();
    }

    void addTileIds(final Double forZ,
                    final Collection<TileSpec> tileSpecs) {

        final LayerResult layerResult = zToLayerResult.computeIfAbsent(forZ, z -> new LayerResult(forZ));

        for (final TileSpec tileSpec : tileSpecs) {
            layerResult.tileIds.add(tileSpec.getTileId());
        }
    }

    void addNonEmptyLabelColors(final Double forZ,
                                final Set<Integer> nonEmptyLabelColors) {
        final LayerResult layerResult = zToLayerResult.computeIfAbsent(forZ, z -> new LayerResult(forZ));
        layerResult.nonEmptyLabelColors.addAll(nonEmptyLabelColors);
    }

    public List<String> getErrorMessageList(final Double forZ) {
        return zToLayerResult.get(forZ).errorMessageList;
    }

    void addErrorMessage(final Double forZ,
                         final String errorMessage) {
        final LayerResult layerResult = zToLayerResult.computeIfAbsent(forZ, z -> new LayerResult(forZ));
        layerResult.errorMessageList.add(errorMessage);
    }

    public void writeLayerResults(final File rootDirectory,
                                  final int partitionId) {
        for (final LayerResult layerResult : zToLayerResult.values()) {
            layerResult.writeToFile(rootDirectory, partitionId);
        }
    }

    public void checkLabelCounts(final Double forZ,
                                 final Set<String> completelyObscuredTileIds) {
        zToLayerResult.get(forZ).checkLabelCount(completelyObscuredTileIds);
    }

    public static LabelBoxValidationResult fromDirectory(final File rootDirectory,
                                                         final Double forZ)  {
        final LabelBoxValidationResult result = new LabelBoxValidationResult();
        result.zToLayerResult.put(forZ, LayerResult.fromDirectory(rootDirectory, forZ));
        return result;
    }

    public static class LayerResult implements Serializable {

        private final Double z;
        private final Set<String> tileIds;
        private final Set<Integer> nonEmptyLabelColors;
        private final List<String> errorMessageList;

        @SuppressWarnings("unused")
        public LayerResult() {
            this(null);
        }

        LayerResult(final Double z) {
            this.z = z;
            this.tileIds = new HashSet<>();
            this.nonEmptyLabelColors = new HashSet<>();
            this.errorMessageList = new ArrayList<>();
        }

        void mergeResult(final LayerResult that) {
            this.tileIds.addAll(that.tileIds);
            this.nonEmptyLabelColors.addAll(that.nonEmptyLabelColors);
            this.errorMessageList.addAll(that.errorMessageList);
        }

        public int getGroup() {
            return z.intValue() / 500;
        }

        File getLayerDirectory(final File rootDirectory) {
            final String groupName = String.format("%06d", getGroup());
            final String layerName = z.toString();
            final Path path = Paths.get(rootDirectory.getAbsolutePath(),
                                        groupName,
                                        layerName);
            return path.toFile();
        }

        void writeToFile(final File rootDirectory,
                         final int partitionId)
                throws RuntimeException {

            final String fileName = String.format("label_val_%06d_%d.json", partitionId, new Date().getTime());
            final File file = new File(getLayerDirectory(rootDirectory), fileName);

            FileUtil.ensureWritableDirectory(file.getParentFile());

            try {
                JsonUtils.FAST_MAPPER.writeValue(file, this);
            } catch (final IOException e) {
                throw new RuntimeException("failed to write validation data to " + file, e);
            }

        }

        void checkLabelCount(final Set<String> completelyObscuredTileIds) {

            final List<Color> colorList = LabelImageProcessorCache.buildColorList();
            final AtomicInteger tileIndex = new AtomicInteger(0);

            tileIds.stream()
                    .sorted()
                    .forEach(tileId -> {
                        final int colorIndex = tileIndex.getAndIncrement();
                        final int tileColor = ((short) colorList.get(colorIndex).getRGB()) & 0xffff;
                        if (! nonEmptyLabelColors.contains(tileColor)) {
                            if (! completelyObscuredTileIds.contains(tileId)) {
                                errorMessageList.add("color " + tileColor + " for tile number " + colorIndex +
                                                     " in layer " + z + " with id " + tileId + " is missing");
                            }
                        }
                    });

        }

        public String toJson() {
            return JSON_HELPER.toJson(this);
        }
        
        static LayerResult fromJson(final Reader json) {
            return JSON_HELPER.fromJson(json);
        }

        static LayerResult fromDirectory(final File rootDirectory,
                                         final Double z) {

            final LayerResult layerResult = new LayerResult(z);
            final File layerDirectory = layerResult.getLayerDirectory(rootDirectory);

            try {

                Files.list(layerDirectory.toPath()).forEach(path -> {
                    try {

                        final File file = path.toFile();

                        if (file.getName().startsWith("label_val_")) {
                            final LayerResult fileResult = fromJson(new FileReader(file));
                            layerResult.mergeResult(fileResult);
                        }

                    } catch (final FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                });

            } catch (final IOException e) {
                throw new RuntimeException("failed to read validation data from " + layerDirectory, e);
            }

            return layerResult;
        }

        private static final JsonUtils.Helper<LayerResult> JSON_HELPER =
                new JsonUtils.Helper<>(LayerResult.class);

    }

    public static void main(final String[] args) {

        final File rootDirectory = new File("/Users/trautmane/Desktop/label_val_1544187869528");
        final double z = 3240.0;
        final LabelBoxValidationResult result = LabelBoxValidationResult.fromDirectory(rootDirectory,
                                                                                       z);
        result.checkLabelCounts(z, new HashSet<>(Arrays.asList("150317175531016190.3240.0","150317175531017190.3240.0")));
        result.getErrorMessageList(z).forEach(System.out::println);
    }

}
