package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for importing DMG tile specifications into the render database.
 *
 * @author Eric Trautman
 */
public class ImportDmgClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of stack from which the DMG data was generated",
                required = true)
        public String stack;

        @Parameter(
                names = "--dmgStack",
                description = "Name of the DMG stack for the imported data",
                required = true)
        public String dmgStack;

        @Parameter(
                names = "--dmgRootPath",
                description = "Root path for data (<dmgRootPath>/<width>x<height>/0/<z>/<z>.0.<row>.<col>.png)",
                required = true)
        public String dmgRootPath;

        @Parameter(
                names = "--width",
                description = "Width of all tiles (<dmgRootPath>/<width>x<height>/0/<z>/<z>.0.<row>.<col>.png)")
        public Integer width = 8192;

        @Parameter(
                names = "--height",
                description = "Height of all tiles (<dmgRootPath>/<width>x<height>/0/<z>/<z>.0.<row>.<col>.png)")
        public Integer height = 8192;

        @Parameter(
                names = "--dmgFormat",
                description = "Format of dmg data (png|jpg|tif)")
        public String dmgFormat = "png";

        @Parameter(
                names = "--completeToStackAfterImport",
                description = "Complete the to stack after importing all layers",
                arity = 0)
        public boolean completeToStackAfterImport = false;

        @Parameter(
                names = "--z",
                description = "Z value of layer to import (use additional --z parameters to specify multiple layers)",
                required = true)
        public List<Double> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportDmgClient client = new ImportDmgClient(parameters);

                for (final Double z : parameters.zValues) {
                    client.importStackData(z);
                }

                if (parameters.completeToStackAfterImport) {
                    client.completeDmgStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private ImportDmgClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);

        // remove mipmap path builder so that it doesn't get copied to derived DMG stack
        if (sourceStackMetaData.getCurrentMipmapPathBuilder() != null) {
            sourceStackMetaData.setCurrentMipmapPathBuilder(null);
        }

        this.renderDataClient.setupDerivedStack(sourceStackMetaData, parameters.dmgStack);
    }

    private void importStackData(final Double z) throws Exception {

        LOG.info("importStackData: entry, z={}", z);

        final List<TileBounds> sourceTileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
        final TileBoundsRTree sourceTileBoundsRTree = new TileBoundsRTree(z, sourceTileBoundsList);

        final List<TileSpec> tileSpecs = new ArrayList<>();

        final double dWidth = parameters.width;
        final double dHeight = parameters.height;

        // <dmgRootPath>/<width>x<height>/0/<z>/<z>.0.<row>.<col>.png
        final Pattern baseNamePattern = Pattern.compile(
                "^" + z + "\\.(?:.*\\.)?(\\d+)\\.(\\d+)\\." + parameters.dmgFormat + "$");

        final Path zPath = Paths.get(parameters.dmgRootPath,
                                     parameters.width + "x" + parameters.height,
                                     "0",
                                     String.valueOf(z.intValue())).toAbsolutePath();

        LOG.info("importStackData: looking for images in {}", zPath);

        Files.list(zPath).forEach(path -> {

            final String baseName = path.getFileName().toString();
            final Matcher m = baseNamePattern.matcher(baseName);
            if (m.matches()) {

                final int row = Integer.parseInt(m.group(1));
                final int col = Integer.parseInt(m.group(2));
                final double minX = col * dWidth;
                final double minY = row * dHeight;

                // DMG creates lots of empty tiles, check source bounds and only load DMG tiles with data ...
                
                final List<TileBounds> sourceBoxTileBounds =
                        sourceTileBoundsRTree.findTilesInBox(minX, minY, (minX + dWidth), (minY + dHeight));

                if (sourceBoxTileBounds.size() > 0) {

                    final String translateDataString = minX + " " + minY;

                    final LeafTransformSpec transformSpec = new LeafTransformSpec(TranslationModel2D.class.getName(),
                                                                                  translateDataString);

                    final ChannelSpec channelSpec = new ChannelSpec("dmg", null, null);
                    channelSpec.putMipmap(0, new ImageAndMask(path.toAbsolutePath().toString(), null));

                    final TileSpec tileSpec = new TileSpec();
                    final LayoutData layoutData =
                            new LayoutData(z.toString(), null, null, row, col, minX, minY, null);
                    tileSpec.setLayout(layoutData);
                    tileSpec.setTileId(baseName);
                    tileSpec.setZ(z);
                    tileSpec.setWidth(dWidth);
                    tileSpec.setHeight(dHeight);
                    tileSpec.addChannel(channelSpec);
                    tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
                    tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

                    tileSpecs.add(tileSpec);

                }

            } else if (baseName.endsWith(parameters.dmgFormat)) {

                LOG.info("importStackData: ignoring {}", baseName);
                
            }

        });

        if (tileSpecs.size() > 0) {
            final ResolvedTileSpecCollection resolvedTiles =
                    new ResolvedTileSpecCollection(new ArrayList<>(), tileSpecs);
            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.dmgStack, z);
        }

        LOG.info("importStackData: exit, imported {} tiles for z {}", tileSpecs.size(), z);
    }

    private void completeDmgStack() throws Exception {
        renderDataClient.setStackState(parameters.dmgStack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportDmgClient.class);
}
