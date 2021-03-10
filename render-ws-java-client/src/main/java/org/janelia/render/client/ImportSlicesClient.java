package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for creating and importing specifications for
 * single tile "slice" images into the render database.
 *
 * @author Eric Trautman
 */
public class ImportSlicesClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of stack for imported data",
                required = true)
        public String stack;

        @Parameter(
                names = "--sliceListing",
                description = "File containing list of absolute slice paths",
                required = true)
        public String sliceListing;

        @Parameter(
                names = "--stackResolutionX",
                description = "X resoution (in nanometers) for the stack"
        )
        public Double stackResolutionX;

        @Parameter(
                names = "--stackResolutionY",
                description = "Y resoution (in nanometers) for the stack"
        )
        public Double stackResolutionY;

        @Parameter(
                names = "--stackResolutionZ",
                description = "Z resoution (in nanometers) for the stack"
        )
        public Double stackResolutionZ;

        @Parameter(
                names = "--deriveSliceSizes",
                description = "Dynamically derive slice height and width by opening each one " +
                              "(omit if all slices have same size)",
                arity = 0)
        public boolean deriveSliceSizes = false;

        @Parameter(
                names = "--completeStackAfterImport",
                description = "Complete the stack after importing all layers",
                arity = 0)
        public boolean completeStackAfterImport = false;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportSlicesClient client = new ImportSlicesClient(parameters);
                client.importStackData();


                if (parameters.completeStackAfterImport) {
                    client.completeStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private ImportSlicesClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void importStackData() throws Exception {

        final File fileListing = new File(parameters.sliceListing).getAbsoluteFile();

        LOG.info("importStackData: loading slice file paths from {}", fileListing);

        final List<String> slicePaths =
                Files.readAllLines(fileListing.toPath()).stream()
                        .sorted()
                        .collect(Collectors.toList());

        LOG.info("importStackData: creating tile specs for {} slices", slicePaths.size());

        final List<TileSpec> tileSpecs = new ArrayList<>(slicePaths.size());

        Double width = null;
        Double height = null;
        for (int i = 0; i < slicePaths.size(); i++) {

            final String slicePath = slicePaths.get(i);
            final Double z = i + 1.0;

            if (parameters.deriveSliceSizes || (width == null)) {
                final ImageProcessor imp = new ImagePlus(slicePath).getProcessor();
                width = (double) imp.getWidth();
                height = (double) imp.getHeight();
            }

            final ChannelSpec channelSpec = new ChannelSpec(null, null, null);
            channelSpec.putMipmap(0, new ImageAndMask(slicePath, null));

            final TileSpec tileSpec = new TileSpec();
            final LayoutData layoutData = new LayoutData(z.toString(),
                                                         null,
                                                         null,
                                                         0,
                                                         0,
                                                         0.0,
                                                         0.0,
                                                         null);
            tileSpec.setLayout(layoutData);
            tileSpec.setTileId("slice-" + i);
            tileSpec.setZ(z);
            tileSpec.setWidth(width);
            tileSpec.setHeight(height);
            tileSpec.addChannel(channelSpec);
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            tileSpecs.add(tileSpec);
        }

        if (tileSpecs.size() > 0) {
            final StackVersion stackVersion = new StackVersion(new Date(),
                                                               null,
                                                               null,
                                                               null,
                                                               parameters.stackResolutionX,
                                                               parameters.stackResolutionY,
                                                               parameters.stackResolutionZ,
                                                               null,
                                                               null,
                                                               null,
                                                               null);

            renderDataClient.saveStackVersion(parameters.stack, stackVersion);

            final ResolvedTileSpecCollection resolvedTiles =
                    new ResolvedTileSpecCollection(new ArrayList<>(), tileSpecs);

            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, null);
        }

        LOG.info("importStackData: exit, imported {} tiles", tileSpecs.size());
    }

    private void completeStack() throws Exception {
        renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportSlicesClient.class);
}
