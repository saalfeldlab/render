package org.janelia.render.client.spark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import mpicbg.trakem2.transform.AffineModel2D;

import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark function for creating a split stack in a hierarchical tier.
 *
 * @author Eric Trautman
 */
public class HierarchicalStackCreationFunction
        implements Function<HierarchicalStack, Integer> {

    private final String baseDataUrl;
    private final String owner;
    private final String tierProject;
    private final StackVersion parentStackVersion;
    private final String versionNotes;
    private final int tier;
    private final Set<StackId> existingStacks;
    private final List<Double> zValues;
    private final String channel;
    private final Double minIntensity;
    private final Double maxIntensity;
    private final String boxUrlPrefix;
    private final String boxUrlSuffix;

    public HierarchicalStackCreationFunction(final String baseDataUrl,
                                             final String owner,
                                             final String tierProject,
                                             final StackVersion parentStackVersion,
                                             final String versionNotes,
                                             final int tier,
                                             final Collection<StackId> existingStacks,
                                             final List<Double> zValues,
                                             final String channel,
                                             final Double minIntensity,
                                             final Double maxIntensity,
                                             final String boxUrlPrefix,
                                             final String boxUrlSuffix) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.tierProject = tierProject;
        this.parentStackVersion = parentStackVersion;
        this.versionNotes = versionNotes;
        this.tier = tier;
        this.existingStacks = new HashSet<>(existingStacks);
        this.zValues = new ArrayList<>(zValues);
        this.channel = channel;
        this.minIntensity = minIntensity;
        this.maxIntensity = maxIntensity;
        this.boxUrlPrefix = boxUrlPrefix;
        this.boxUrlSuffix = boxUrlSuffix;
    }

    @Override
    public Integer call(final HierarchicalStack splitStack)
            throws Exception {

        final ProcessTimer timer = new ProcessTimer();

        final StackId splitStackId = splitStack.getSplitStackId();
        final String stack = splitStackId.getStack();
        LogUtilities.setupExecutorLog4j(stack);

        final Logger log = LoggerFactory.getLogger(HierarchicalStackCreationFunction.class);

        int tileCount = 0;

        final RenderDataClient tierDataClient = new RenderDataClient(baseDataUrl,
                                                                     owner,
                                                                     tierProject);

        if (existingStacks.contains(splitStackId)) {

            log.info("call: skipping stack creation because it already exists");

        } else {

            log.info("call: creating stack with {} layers", zValues.size());

            final StackVersion stackVersion = new StackVersion(new Date(),
                                                               versionNotes,
                                                               tier,
                                                               null,
                                                               parentStackVersion.getStackResolutionX(),
                                                               parentStackVersion.getStackResolutionY(),
                                                               parentStackVersion.getStackResolutionZ(),
                                                               null,
                                                               null);
            tierDataClient.saveStackVersion(stack, stackVersion);

            tierDataClient.setHierarchicalData(stack, splitStack);

            final TransformSpecMetaData transformSpecMetaData = new TransformSpecMetaData();
            transformSpecMetaData.addLabel("regular");

            final double scale = splitStack.getScale();
            final Bounds splitStackFullScaleBounds = splitStack.getFullScaleBounds();
            final double scaledMinX = scale * splitStackFullScaleBounds.getMinX();
            final double scaledMinY = scale * splitStackFullScaleBounds.getMinY();

//            final double fullScaleWidthDiv2 = splitStackFullScaleBounds.getDeltaX() / 2.0;
//            final double fullScaleHeightDiv2 = splitStackFullScaleBounds.getDeltaY() / 2.0;
//            final double scaledCenteredOnOriginX = scale * -fullScaleWidthDiv2;
//            final double scaledCenteredOnOriginY = scale * -fullScaleHeightDiv2;

            final List<TransformSpec> regularTransform =
                    Collections.singletonList(
                            new LeafTransformSpec(null,
                                                  transformSpecMetaData,
                                                  AffineModel2D.class.getName(),
                                                  "1 0 0 1 " + scaledMinX + " " + scaledMinY));

            final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection();
            TileSpec tileSpec;
            ChannelSpec channelSpec;
            String boxUrl;
            for (final double z : zValues) {

                tileSpec = new TileSpec();

                tileSpec.setTileId(splitStack.getTileIdForZ(z));
                tileSpec.setLayout(new LayoutData(String.valueOf(z), "n/a", "n/a", 0, 0, scaledMinX, scaledMinY, 0.0));
                tileSpec.setZ(z);

                channelSpec = new ChannelSpec(channel,
                                              minIntensity,
                                              maxIntensity,
                                              new TreeMap<>(),
                                              null);

                boxUrl = boxUrlPrefix + splitStack.getBoxPathForZ(z) + boxUrlSuffix + (int) z + ".tif";
                channelSpec.putMipmap(0, new ImageAndMask(boxUrl, null));

                tileSpec.addChannel(channelSpec);

                tileSpec.addTransformSpecs(regularTransform);
                splitStack.setTileSpecBounds(tileSpec);

                resolvedTiles.addTileSpecToCollection(tileSpec);
            }

            tierDataClient.saveResolvedTiles(resolvedTiles, stack, null);

            tierDataClient.setStackState(stack, StackMetaData.StackState.COMPLETE);

            tileCount = resolvedTiles.getTileCount();

            log.info("call: exit, created {} layer canvas tiles in {} milliseconds",
                     tileCount, timer.getElapsedMilliseconds());
        }

        return tileCount;
    }
}
