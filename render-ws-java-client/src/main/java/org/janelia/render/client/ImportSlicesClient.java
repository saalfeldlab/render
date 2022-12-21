package org.janelia.render.client;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import mpicbg.trakem2.transform.TranslationModel2D;

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
                description = "File containing list of absolute slice paths")
        public String sliceListing;

        @Parameter(
                names = "--sliceUrlFormat",
                description = "Input URL format for slice (assumes integral z value).  " +
                              "For example: /nrs/flyem/alignment-legacy/Z0720-07m/BR/Sec37/aligned/after.%05d.png " +
                              "This is ignored if a sliceListing is specified.")
        public String sliceUrlFormat;

        @Parameter(
                names = "--sliceZOffset",
                description = "If specified, this value will be added to stack z values before formatting " +
                              "slice URLs (e.g. a value of -1 would map z 1 to after.00000.png)")
        public Integer sliceZOffset;

        @Parameter(
                names = "--basisStack",
                description = "Existing stack (in same project) to use as basis for the new imported stack " +
                              "(e.g. for layer tile positions and resolution data)")
        public String basisStack;

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

        public void validate() throws IllegalArgumentException {
            if ((sliceListing == null) && (sliceUrlFormat == null)) {
                throw new IllegalArgumentException("must specify --sliceListing or --sliceUrlFormat");
            }
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.validate();

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

        final StackMetaData basisStackMetaData = parameters.basisStack == null ? null :
                                                 this.renderDataClient.getStackMetaData(parameters.basisStack);

        final Map<Double, String> zToSlicePath = new LinkedHashMap<>();
        final List<TransformSpec> stackTransformSpecList;

        if (basisStackMetaData == null) {

            // load explicit slice data

            final File fileListing = new File(parameters.sliceListing).getAbsoluteFile();

            LOG.info("importStackData: loading slice file paths from {}", fileListing);

            final List<String> slicePaths =
                    Files.readAllLines(fileListing.toPath()).stream()
                            .sorted()
                            .collect(Collectors.toList());
            for (int i = 0; i < slicePaths.size(); i++) {
                zToSlicePath.put(i + 1.0, slicePaths.get(i));
            }
            stackTransformSpecList = null;

            if (zToSlicePath.size() > 0) {
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
            }

        } else {

            // load slice data using basis stack
            final int zOffset = parameters.sliceZOffset == null ? 0 : parameters.sliceZOffset;
            for (final Double z : renderDataClient.getStackZValues(parameters.basisStack)) {
                final String slicePath = String.format(parameters.sliceUrlFormat, z.intValue() + zOffset);
                final File sliceFile = new File(slicePath).getAbsoluteFile();
                if (! sliceFile.exists()) {
                    throw new IllegalArgumentException(sliceFile + " does not exist");
                }
                zToSlicePath.put(z, sliceFile.getAbsolutePath());
            }

            final Bounds bounds = basisStackMetaData.getStats().getStackBounds();
            final TranslationModel2D model = new TranslationModel2D();
            model.set(bounds.getMinX(), bounds.getMinY());
            final String modelDataString = model.toDataString();
            final LeafTransformSpec stackTransform = new LeafTransformSpec(model.getClass().getName(),
                                                                           modelDataString);
            stackTransformSpecList = Collections.singletonList(stackTransform);

            if (zToSlicePath.size() > 0) {
                renderDataClient.setupDerivedStack(basisStackMetaData, parameters.stack);
            }

        }

        LOG.info("importStackData: creating tile specs for {} slices", zToSlicePath.size());

        final List<TileSpec> tileSpecs = new ArrayList<>(zToSlicePath.size());

        Double width = null;
        Double height = null;
        for (final Double z : zToSlicePath.keySet()) {

            final String slicePath = zToSlicePath.get(z);

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
            tileSpec.setTileId("slice." + z);
            tileSpec.setZ(z);
            tileSpec.setWidth(width);
            tileSpec.setHeight(height);
            tileSpec.addChannel(channelSpec);

            if (stackTransformSpecList != null) {
                tileSpec.addTransformSpecs(stackTransformSpecList);
            }

            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            tileSpecs.add(tileSpec);
        }

        if (tileSpecs.size() > 0) {
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
