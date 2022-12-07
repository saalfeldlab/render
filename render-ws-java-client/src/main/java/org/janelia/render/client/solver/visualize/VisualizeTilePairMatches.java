package org.janelia.render.client.solver.visualize;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.core.JsonProcessingException;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.imagefeatures.Feature;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageDebugUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.RealRandomAccess;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Java client for visualizing matches for a tile pair.
 */
public class VisualizeTilePairMatches {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();
        @Parameter(names = "--stack",       description = "Stack name", required = true) public String stack;
        @Parameter(names = "--pTileId",     description = "P tile identifier", required = true) public String pTileId;
        @Parameter(names = "--qTileId",     description = "Q tile identifier", required = true) public String qTileId;
        @Parameter(names = "--collection",  description = "Match collection name") public String collection;
        @Parameter(names = "--renderScale", description = "Scale to render tiles and matches") public Double renderScale = 1.0;
        @Parameter(names = "--renderWithFilter", description = "Render tiles with filter") public boolean renderWithFilter = false;
        @Parameter(
                names = "--renderMfovWithSize",
                description = "If specified, render all tiles in specified tiles' MFOV " +
                              "and scale max dimension of result to this size (omit to just render tiles)")
        public Integer renderMfovWithSize;

        @Parameter(
                names = "--alignWithPlugin",
                description = "Run ImageJ Linear Stack Alignment with SIFT plugin with default parameters on tiles")
        public boolean alignWithPlugin = false;

        @Parameter(
                names = "--alignWithRender",
                description = "Run render match process dynamically on tiles")
        public boolean alignWithRender = false;

        @ParametersDelegate
        public MatchDerivationParameters match = new MatchDerivationParameters();

        @ParametersDelegate
        public FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        public Parameters() {
        }
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            args = new String[] {
                    /*
                    // -----------------------------------------
                    // parameters for MFOV debug
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "hess",
                    "--project", "wafer_52c",
                    "--stack", "v1_acquire_slab_001_align_w0p1",
                    "--pTileId", "001_000004_005_20220407_224812.1249.0",
                    "--qTileId", "001_000004_005_20220408_060427.1250.0",
                    "--renderMfovWithSize", "1024",
                    "--alignWithRender",
                    "--matchRod", "0.92",
                    "--matchModelType", "RIGID",
                    "--matchIterations", "1000",
                    "--matchMaxEpsilonFullScale", "2",
                    "--matchMinInlierRatio", "0",
                    "--matchMinNumInliers", "20",
                    "--matchMaxTrust", "4",
                    "--matchFilter", "SINGLE_SET",
                    "--SIFTfdSize", "4",
                    "--SIFTminScale", "0.0125",
                    "--SIFTmaxScale", "1.0",
                    "--SIFTsteps", "5",
                     */

                    // -----------------------------------------
                    // parameters for multi-SEM tile pair debug
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "hess",
                    "--project", "wafer_52c",
                    "--stack", "v1_acquire_001_000003",

//                    "--pTileId", "001_000003_001_20220407_224810.1249.0",
//                    "--qTileId", "001_000003_001_20220408_060424.1250.0",

//                    "--pTileId", "001_000003_026_20220407_224810.1249.0",
//                    "--qTileId", "001_000003_026_20220408_060424.1250.0",
                    
//                    "--pTileId", "001_000003_062_20220407_224810.1249.0",
//                    "--qTileId", "001_000003_062_20220408_060424.1250.0",

                    "--pTileId", "001_000003_067_20220407_224810.1249.0",
                    "--qTileId", "001_000003_067_20220408_060424.1250.0",

//                    "--alignWithPlugin",
                    "--collection", "wafer_52c_mfov3_cross_inverted",
                    "--renderScale", "1.0",
//                    "--renderWithFilter",

                    /*
                    // -----------------------------------------
                    // dynamic derivation with exact Sec24_v1 crossPass2 parameters
                    // match distances: mean 2.10, max 13.08
                    "--alignWithRender",
                    "--renderScale", "0.1",
                    //"--renderWithFilter",
                    "--matchRod", "0.92",
                    "--matchModelType", "RIGID",
                    "--matchIterations", "1000",
                    "--matchMaxEpsilonFullScale", "10",
                    "--matchMinInlierRatio", "0",
                    "--matchMinNumInliers", "20",
                    "--matchMaxTrust", "4",
                    "--matchFilter", "AGGREGATED_CONSENSUS_SETS",
                    "--SIFTfdSize", "8",
                    "--SIFTminScale", "0.125",
                    "--SIFTmaxScale", "1.0",
                    "--SIFTsteps", "5",
					*/
                    /*
                    // -----------------------------------------
                    // dynamic derivation with full scale Sec24_v1 crossPass2 parameters to avoid mipmaps
                    // match distances: mean 2.02, max 10.11
                    "--alignWithRender",
                    "--renderScale", "1.0",
                    //"--renderWithFilter",
                    "--matchRod", "0.92",
                    "--matchModelType", "RIGID",
                    "--matchIterations", "1000",
                    "--matchMaxEpsilonFullScale", "10",
                    "--matchMinInlierRatio", "0",
                    "--matchMinNumInliers", "20",
                    "--matchMaxTrust", "4",
                    "--matchFilter", "AGGREGATED_CONSENSUS_SETS",
                    "--SIFTfdSize", "8",
                    "--SIFTminScale", "0.0125", // "0.125" with renderScale 0.1
                    "--SIFTmaxScale", "0.1",    // "1.0" with renderScale 0.1
                    "--SIFTsteps", "5",
                    */
                    // -----------------------------------------
                    // dynamic derivation with adapted plugin parameters that produces good result
                    // match distances: mean 1.76, max 9.04
//                    "--alignWithRender",
//                    "--renderScale", "1.0",
//                    "--matchRod", "0.92",
//                    "--matchModelType", "RIGID",
//                    "--matchIterations", "1000",
//                    "--matchMaxEpsilonFullScale", "25",
//                    "--matchMinInlierRatio", "0",
//                    "--matchMinNumInliers", "40",
//                    "--matchMaxTrust", "4",
//                    "--matchFilter", "SINGLE_SET",
//                    "--SIFTfdSize", "8", // "4",
//                    "--SIFTminScale", "0.0186", // "0.0075",
//                    "--SIFTmaxScale", "0.1187", // "0.12",
//                    "--SIFTsteps", "3",
            };
        }

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final VisualizeTilePairMatches client = new VisualizeTilePairMatches(parameters);
                client.go();
            }
        };

        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final ImageProcessorCache imageProcessorCache;

    VisualizeTilePairMatches(final Parameters parameters)
            throws IllegalArgumentException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = parameters.collection == null ? null :
                               new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.renderWeb.owner,
                                                    parameters.collection);
        // using cache helps a little with loading large masks over VPN
        this.imageProcessorCache =
                new ImageProcessorCache(4 * 15000 * 10000, // 4 big images
                                        true,
                                        false);
        if (parameters.alignWithRender) {
            parameters.match.validateAndSetDefaults("");
            parameters.featureExtraction.setDefaults();
        }
    }

    private void go()
            throws IOException, NotEnoughDataPointsException, IllDefinedDataPointsException {

        new ImageJ();

        final DebugTile pTile = new DebugTile(parameters.pTileId);
        final DebugTile qTile = new DebugTile(parameters.qTileId);

        final ImageProcessor pIp = pTile.render();
        final ImageProcessor qIp = qTile.render();

        // double height and width of view to handle offset tile overlaps as found in multi-SEM data
        final int width = Math.max(pIp.getWidth(), qIp.getWidth()) * 2;
        final int height = Math.max(pIp.getHeight(), qIp.getHeight()) * 2;

        // later visualization stuff assumes float, so convert here to simplify
        final ImageProcessor pSlice = padProcessor(pIp, width, height).convertToFloat();
        final ImageProcessor qSlice = padProcessor(qIp, width, height).convertToFloat();

        if (parameters.collection != null) {
            showMatchesAndAlign(pTile, qTile, pSlice, qSlice, true);
        }

        if (parameters.alignWithRender) {
            showMatchesAndAlign(pTile, qTile, pSlice, qSlice, false);
        }

        if (parameters.alignWithPlugin) {
            final ImageStack imageStack = new ImageStack(width, height);
            imageStack.addSlice("Q:" + parameters.qTileId, qSlice);
            imageStack.addSlice("P:" + parameters.pTileId, pSlice);

            final ImagePlus imageStackPlus = new ImagePlus("Source Stack", imageStack);

            final SIFTAlignDebug plugin = new SIFTAlignDebug();
            final SIFTAlignDebug.Param p = new SIFTAlignDebug.Param();
            p.showMatrix = true;
            plugin.runWithParameters(imageStackPlus, p);

            final List<PointMatch> pluginInliers = plugin.getInliers();
            System.out.println("plugin mean match distance: " + PointMatch.meanDistance(pluginInliers));
            System.out.println("plugin max match distance: " + PointMatch.maxDistance(pluginInliers));
        }

        System.out.println("visualizations complete: kill process when done viewing");

        SimpleMultiThreading.threadHaltUnClean();
    }

    private void showMatchesAndAlign(final DebugTile pTile,
                                     final DebugTile qTile,
                                     final ImageProcessor pSlice,
                                     final ImageProcessor qSlice,
                                     final boolean showSaved)
            throws IOException, NotEnoughDataPointsException, IllDefinedDataPointsException {

        final String titlePrefix;
        final List<PointMatch> fullScalePointMatchList;
        if (showSaved) {

            titlePrefix = parameters.collection;

            final CanvasMatches canvasMatches =
                    matchDataClient.getMatchesBetweenTiles(pTile.getGroupId(), pTile.getId(),
                                                           qTile.getGroupId(), qTile.getId());
            fullScalePointMatchList = CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());

        } else {

            titlePrefix = "dynamic";

            new ImagePlus("p", pSlice ).show();
            new ImagePlus("q", qSlice ).show();

            //net.imglib2.multithreading.SimpleMultiThreading.threadHaltUnClean();
            
            final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(parameters.featureExtraction);
            final List<Feature> pFeatureList = featureExtractor.extractFeaturesFromImageAndMask(pSlice,
                                                                                                null);
            final List<Feature> qFeatureList = featureExtractor.extractFeaturesFromImageAndMask(qSlice,
                                                                                                null);
            //net.imglib2.multithreading.SimpleMultiThreading.threadHaltUnClean();
            final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(parameters.match,
                                                                                 parameters.renderScale);
            final CanvasMatchResult matchResult = featureMatcher.deriveMatchResult(pFeatureList, qFeatureList);

            if (parameters.renderScale != 1.0) {
                final Matches fullScaleMatches =
                        CanvasMatchResult.convertPointMatchListToMatches(matchResult.getInlierPointMatchList(),
                                                                         parameters.renderScale);
                fullScalePointMatchList = CanvasMatchResult.convertMatchesToPointMatchList(fullScaleMatches);
            }  else {
                fullScalePointMatchList = matchResult.getInlierPointMatchList();
            }

        }

        final AbstractAffineModel2D<?> fullScaleModel = new AffineModel2D(); // NOTE: using rigid instead of affine here makes derived and plugin results very similar
        fullScaleModel.fit(fullScalePointMatchList); // The estimated model transfers match.p1.local to match.p2.world
        System.out.println(titlePrefix + " full scale model: " + fullScaleModel);

        for (final PointMatch pm : fullScalePointMatchList) {
            pm.getP1().apply(fullScaleModel);
        }

        System.out.println(titlePrefix + " full scale mean match distance: " + PointMatch.meanDistance(fullScalePointMatchList));
        System.out.println(titlePrefix + " full scale max match distance: " + PointMatch.maxDistance(fullScalePointMatchList));

        final int widthOffset = pSlice.getWidth() / 4;
        final int heightOffset = pSlice.getHeight() / 4;
        final List<Point> pPointList = new ArrayList<>(fullScalePointMatchList.size());
        final List<Point> qPointList = new ArrayList<>(fullScalePointMatchList.size());
        fullScalePointMatchList.forEach(pm -> {
            final Point pPoint = pm.getP1();
            final Point qPoint = pm.getP2();
            if (parameters.renderScale != 1.0) {
                scaleLocal(pPoint);
                scaleLocal(qPoint);
            }
            pPoint.getL()[0] += widthOffset;
            pPoint.getL()[1] += heightOffset;
            qPoint.getL()[0] += widthOffset;
            qPoint.getL()[1] += heightOffset;
            pPointList.add(pPoint);
            qPointList.add(qPoint);
        });

        final ImagePlus pSourcePlus = new ImagePlus(titlePrefix + " P Points:" + parameters.pTileId, pSlice);
        final ImagePlus qSourcePlus = new ImagePlus(titlePrefix + " Q Points:" + parameters.qTileId, qSlice);
        ImageDebugUtil.setPointRois(pPointList, pSourcePlus);
        ImageDebugUtil.setPointRois(qPointList, qSourcePlus);

        pSourcePlus.show();
        qSourcePlus.show();

        int width = pSlice.getWidth();
        int height = pSlice.getHeight();
        ImageProcessor pSliceFullScale = pSlice;
        ImageProcessor qSliceFullScale = qSlice;
        if (parameters.renderScale != 1.0) {
            pSliceFullScale = pTile.renderFullScale();
            qSliceFullScale = qTile.renderFullScale();
            width = Math.max(pSliceFullScale.getWidth(), qSliceFullScale.getWidth());
            height = Math.max(pSliceFullScale.getHeight(), qSliceFullScale.getHeight());
            pSliceFullScale = padProcessor(pSliceFullScale, width, height);
            qSliceFullScale = padProcessor(qSliceFullScale, width, height);
        }
        
        final double[] tmp = new double[ 2 ];
        final AbstractAffineModel2D<?> modelInvert = fullScaleModel.createInverse();

        final ImageProcessor pSliceTransformed = pSliceFullScale.createProcessor(width, height);
        final RealRandomAccess<FloatType> r = Views.interpolate(
                Views.extendZero( ArrayImgs.floats((float[]) pSliceFullScale.getPixels(), width, height) ),
                new NLinearInterpolatorFactory<>() ).realRandomAccess();

        for ( int y = 0; y < pSliceTransformed.getHeight(); ++y )
            for ( int x = 0; x < pSliceTransformed.getWidth(); ++x )
            {
                tmp[ 0 ] = x;
                tmp[ 1 ] = y;
                modelInvert.applyInPlace( tmp );
                r.setPosition( tmp );
                pSliceTransformed.setf(x, y, r.get().get() );
            }

//        final ImagePlus pTransformedPlus = new ImagePlus("TransformedP:" + parameters.pTileId, pSliceTransformed);
//        pTransformedPlus.show();

        final ImageStack transformedImageStack = new ImageStack(width, height);
        transformedImageStack.addSlice("TransformedP:" + parameters.pTileId, pSliceTransformed);
        transformedImageStack.addSlice("Q:" + parameters.qTileId, qSliceFullScale);

        final ImagePlus transformedImageStackPlus = new ImagePlus(titlePrefix + " Aligned Stack",
                                                                  transformedImageStack);

        transformedImageStackPlus.show();

        final ImagePlus[] imagesToMerge = {
                null,                                                                  // red
                new ImagePlus(titlePrefix + " Q", qSliceFullScale),               // green
                null,                                                                  // blue
                null,                                                                  // gray
                null,                                                                  // cyan
                new ImagePlus(titlePrefix + " P Transformed", pSliceTransformed), // magenta
                null                                                                   // yellow
        };

        final RGBStackMerge mergePlugin = new RGBStackMerge();
        final ImagePlus mergedPlus = mergePlugin.mergeHyperstacks(imagesToMerge, false);
        mergedPlus.setTitle(titlePrefix + " Merged");
        mergedPlus.show();

    }

    private ImageProcessor padProcessor(final ImageProcessor imageProcessor,
                                        final int width,
                                        final int height) {
        ImageProcessor paddedProcessor = imageProcessor;
        if ((imageProcessor.getWidth() != width) || (imageProcessor.getHeight() != height)) {
            paddedProcessor = imageProcessor.createProcessor(width, height);
            // center tile in larger area
            paddedProcessor.insert(imageProcessor, width / 4, height / 4);
        }
        return paddedProcessor;
    }

    private void scaleLocal(final Point point) {
        final double[] pLocal = point.getL();
        for (int i = 0; i < pLocal.length; i++) {
            pLocal[i] = pLocal[i] * parameters.renderScale;
        }
    }

    private class DebugTile {

        final RenderParameters renderParameters;
        final TileSpec tileSpec;

        public DebugTile(final String tileId)
                throws IOException {

            if (parameters.renderMfovWithSize == null) {

                final String tileUrl = renderDataClient.getUrls().getTileUrlString(parameters.stack, tileId) +
                                       "/render-parameters?normalizeForMatching=true&filter=" +
                                       parameters.renderWithFilter + "&scale=" + parameters.renderScale;
                this.renderParameters = RenderParameters.loadFromUrl(tileUrl);
                this.renderParameters.initializeDerivedValues();
                this.tileSpec = renderParameters.getTileSpecs().get(0);

            } else {

                this.tileSpec = renderDataClient.getTile(parameters.stack, tileId);
                this.renderParameters = renderDataClient.getRenderParametersForZ(parameters.stack, tileSpec.getZ());
                final String mfovId = tileSpec.getTileId().substring(0, 10);

                Bounds mfovBounds = tileSpec.toTileBounds();
                final Set<String> tileIdsToKeep = new HashSet<>();
                for (final TileSpec layerTileSpec : renderParameters.getTileSpecs()) {
                    if (layerTileSpec.getTileId().startsWith(mfovId)) {
                        tileIdsToKeep.add(layerTileSpec.getTileId());
                        mfovBounds = mfovBounds.union(layerTileSpec.toTileBounds());
                    }
                }
                this.renderParameters.removeTileSpecsOutsideSet(tileIdsToKeep);

                this.renderParameters.x = mfovBounds.getMinX();
                this.renderParameters.y = mfovBounds.getMinY();
                this.renderParameters.width = mfovBounds.getWidth();
                this.renderParameters.height = mfovBounds.getHeight();

                final double maxMfovDimension = Math.max(renderParameters.width, renderParameters.height);
                if (maxMfovDimension > parameters.renderMfovWithSize) {
                    this.renderParameters.scale = parameters.renderMfovWithSize / maxMfovDimension;
                } else {
                    this.renderParameters.scale = 1.0;
                }

                this.renderParameters.setDoFilter(parameters.renderWithFilter);
                this.renderParameters.initializeDerivedValues();

                LOG.debug("DebugTile: using renderMfovWithSize {}, derived scale is {}",
                          parameters.renderMfovWithSize, renderParameters.scale);
            }
        }

        public String getGroupId() {
            return tileSpec.getLayout().getSectionId();
        }

        public String getId() {
            return tileSpec.getTileId();
        }

        public ImageProcessor render() {
            final TransformMeshMappingWithMasks.ImageProcessorWithMasks
                    ipwm = Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache);
            return ipwm.ip;
        }

        public ImageProcessor renderFullScale()
                throws JsonProcessingException {
            final ImageProcessor renderedTile;
            if (parameters.renderScale == 1.0) {
                renderedTile = this.render();
            } else {
                final RenderParameters fullScaleRenderParameters =
                        RenderParameters.parseJson(renderParameters.toJson());
                fullScaleRenderParameters.setScale(1.0);
                fullScaleRenderParameters.setDoFilter(false); // force filter off so it is easier to see problems in merged view
                fullScaleRenderParameters.initializeDerivedValues();
                final TransformMeshMappingWithMasks.ImageProcessorWithMasks
                        ipwm = Renderer.renderImageProcessorWithMasks(fullScaleRenderParameters, imageProcessorCache);
                renderedTile = ipwm.ip;
            }
            return renderedTile;
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(VisualizeTilePairMatches.class);
}
