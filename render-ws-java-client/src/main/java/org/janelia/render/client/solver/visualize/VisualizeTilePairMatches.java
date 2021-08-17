package org.janelia.render.client.solver.visualize;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
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
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
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

        @Parameter(
                names = "--firstCanvasPosition",
                description = "When clipping, identifies the relative position of the first canvas to the second canvas"
        )
        public MontageRelativePosition firstCanvasPosition;

        public Parameters() {
        }
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            args = new String[] {
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "Z0720_07m_BR",
                    "--project", "Sec24",
                    "--stack", "v3_acquire_trimmed",
                    "--collection", "Sec24_wobble_fix_5", // "Sec24_wobble_fiji", // "Sec24_v1"
                    "--pTileId", "21-04-29_151034_0-0-0.57325.0",
                    "--qTileId", "21-04-29_151547_0-0-0.57326.0",
//                    "--renderScale", "0.1",
                    "--alignWithPlugin",
                    "--alignWithRender",
                    "--matchRod", "0.92",
                    "--matchModelType", "RIGID",
                    "--matchIterations", "1000",
                    "--matchMaxEpsilonFullScale", "25",
                    "--matchMinInlierRatio", "0",
                    "--matchMinNumInliers", "40",
                    "--matchMaxTrust", "4",
                    "--matchFilter", "SINGLE_SET",
                    "--SIFTfdSize", "4",
                    "--SIFTminScale", "0.0186", // "0.0075",
                    "--SIFTmaxScale", "0.1187", // "0.12",
                    "--SIFTsteps", "3",
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

        final int width = Math.max(pIp.getWidth(), qIp.getWidth());
        final int height = Math.max(pIp.getHeight(), qIp.getHeight());

        final ImageProcessor pSlice = pIp.createProcessor(width, height);
        pSlice.insert(pIp, 0, 0);
        final ImageProcessor qSlice = qIp.createProcessor(width, height);
        qSlice.insert(qIp, 0, 0);

        if (parameters.collection != null) {
            showMatchesAndAlign(pTile, qTile, width, height, pSlice, qSlice, true);
        }

        if (parameters.alignWithRender) {
            showMatchesAndAlign(pTile, qTile, width, height, pSlice, qSlice, false);
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

        SimpleMultiThreading.threadHaltUnClean();
    }

    private void showMatchesAndAlign(final DebugTile pTile,
                                     final DebugTile qTile,
                                     final int width,
                                     final int height,
                                     final ImageProcessor pSlice,
                                     final ImageProcessor qSlice,
                                     final boolean showSaved)
            throws IOException, NotEnoughDataPointsException, IllDefinedDataPointsException {

        final String titlePrefix;
        final List<PointMatch> pointMatchList;
        if (showSaved) {

            titlePrefix = parameters.collection;

            final CanvasMatches canvasMatches =
                    matchDataClient.getMatchesBetweenTiles(pTile.getGroupId(), pTile.getId(),
                                                           qTile.getGroupId(), qTile.getId());
            pointMatchList = CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());

        } else {

            titlePrefix = "dynamic";

            final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(parameters.featureExtraction);
            final List<Feature> pFeatureList = featureExtractor.extractFeaturesFromImageAndMask(pSlice,
                                                                                                null);
            final List<Feature> qFeatureList = featureExtractor.extractFeaturesFromImageAndMask(qSlice,
                                                                                                null);
            final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(parameters.match,
                                                                                 parameters.renderScale);
            final CanvasMatchResult matchResult = featureMatcher.deriveMatchResult(pFeatureList, qFeatureList);
            pointMatchList = matchResult.getInlierPointMatchList();

        }

        final AffineModel2D model = new AffineModel2D();
        model.fit(pointMatchList); // The estimated model transfers match.p1.local to match.p2.world
        System.out.println(titlePrefix + " model: " + model);

        //final List<PointMatch> pointMatchList2 = new ArrayList<>();

        for ( final PointMatch pm : pointMatchList )
        {
            pm.getP1().apply( model );
        }

        System.out.println(titlePrefix + " mean match distance: " + PointMatch.meanDistance(pointMatchList));
        System.out.println(titlePrefix + " max match distance: " + PointMatch.maxDistance(pointMatchList));

//        pointMatchList.clear();
//        pointMatchList.addAll( pointMatchList2 );
//
//        model.fit(pointMatchList);
//        System.out.println( model );
//
//        for ( final PointMatch pm : pointMatchList )
//            pm.getP1().apply( model );
//
//        System.out.println( "mean dist: " + PointMatch.meanDistance( pointMatchList ) );
//        System.out.println( "max dist: " + PointMatch.maxDistance( pointMatchList ) );*/
//
//        System.exit(0 );

        final List<Point> pPointList = new ArrayList<>(pointMatchList.size());
        final List<Point> qPointList = new ArrayList<>(pointMatchList.size());
        pointMatchList.forEach(pm -> {
            final Point pPoint = pm.getP1();
            final Point qPoint = pm.getP2();
            if (parameters.renderScale != 1.0) {
                scaleLocal(pPoint);
                scaleLocal(qPoint);
            }
            pPointList.add(pPoint);
            qPointList.add(qPoint);
        });

        final ImagePlus pSourcePlus = new ImagePlus(titlePrefix + " P Points:" + parameters.pTileId, pSlice);
        final ImagePlus qSourcePlus = new ImagePlus(titlePrefix + " Q Points:" + parameters.qTileId, qSlice);
        ImageDebugUtil.setPointRois(pPointList, pSourcePlus);
        ImageDebugUtil.setPointRois(qPointList, qSourcePlus);

        pSourcePlus.show();
        qSourcePlus.show();

        final double[] tmp = new double[ 2 ];
        final AffineModel2D modelInvert = model.createInverse();

        final ImageProcessor pSliceTransformed = pSlice.createProcessor(width, height);
        final RealRandomAccess<FloatType> r = Views.interpolate(
                Views.extendZero( ArrayImgs.floats((float[]) pSlice.getPixels(), width, height) ),
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
        transformedImageStack.addSlice("Q:" + parameters.qTileId, qSlice);

        final ImagePlus transformedImageStackPlus = new ImagePlus(titlePrefix + " Aligned Stack",
                                                                  transformedImageStack);

        transformedImageStackPlus.show();
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

        public DebugTile(final String tileId) {
            final String tileUrl = renderDataClient.getUrls().getTileUrlString(parameters.stack, tileId) +
                                   "/render-parameters?normalizeForMatching=true&scale=" + parameters.renderScale;
            this.renderParameters = RenderParameters.loadFromUrl(tileUrl);
            this.renderParameters.initializeDerivedValues();
            this.tileSpec = renderParameters.getTileSpecs().get(0);
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
    }

    private static final Logger LOG = LoggerFactory.getLogger(VisualizeTilePairMatches.class);
}
