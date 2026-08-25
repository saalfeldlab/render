package org.janelia.render.client.newsolver.solvers.intensity;

import java.util.HashMap;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.parameter.AlgorithmicIntensityAdjustParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZDistanceParameters;

/**
 * Test (that fails) to help debug a histogram intensity correction issue.
 * Committing it in its broken state just in case we need to come back to it later.
 * The original problem it was developed for has been fixed.
 */
public class IntensityMatcherTest {

    public static void main(final String[] args) {

        final MatchFilter matchFilter = new HistogramMatchFilter();

        final RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();
        renderWeb.baseDataUrl =  "http://renderer.int.janelia.org:8080/render-ws/v1";
        renderWeb.owner = "hess_wafers_60_61";
        renderWeb.project = "w61_serial_100_to_109";

        final String renderStack = "w61_s109_r00_gc_par_align";
        final TileSpec p1 = TileSpec.fromJson(P_TILE_SPEC_JSON);
        final TileSpec p2 = TileSpec.fromJson(Q_TILE_SPEC_JSON);

        final AlgorithmicIntensityAdjustParameters intensityAdjust = new AlgorithmicIntensityAdjustParameters();
        intensityAdjust.maxAllowedError = 0.01;
        intensityAdjust.maxIterations = 10000;
        intensityAdjust.maxPlateauWidth = 2000;
        intensityAdjust.lambda1 = 0.01;
        intensityAdjust.lambda2 = 0.01;
        intensityAdjust.maxPixelCacheGb = 1;
        intensityAdjust.renderScale = 0.1;
        intensityAdjust.zDistance = new ZDistanceParameters(); // same as "simpleZDistance": [0] in JSON
        intensityAdjust.numCoefficients = 4;
        intensityAdjust.equilibrationWeight = 0.0;

        intensityAdjust.crossLayerRenderScale = 0.0;

        final FIBSEMIntensityCorrectionParameters<Object> intensityParameters =
                new FIBSEMIntensityCorrectionParameters<>(null, renderWeb, intensityAdjust);

        final int meshResolution = (int) RenderParameters.DEFAULT_MESH_CELL_SIZE;

        final HashMap<String, IntensityTile> coefficientTiles = new HashMap<>();

        final IntensityMatcher matcher = new IntensityMatcher(matchFilter,
                                                              matchFilter,
                                                              intensityParameters,
                                                              meshResolution,
                                                              0);


        matcher.match(renderStack, p1, p2, coefficientTiles);
    }

    private static final String P_TILE_SPEC_JSON = """
                    {
                    "tileId": "w61_magc0145_scan046_m0000_r19_s23",
                    "layout": {
                    "sectionId": "42.0",
                    "imageRow": 33,
                    "imageCol": 13,
                    "stageX": -70298.15398095643,
                    "stageY": -40518.08008146928
                    },
                    "z": 42,
                    "minX": 14293,
                    "minY": 31631,
                    "maxX": 16297,
                    "maxY": 33379,
                    "width": 2000,
                    "height": 1748,
                    "minIntensity": 0,
                    "maxIntensity": 255,
                    "mipmapLevels": {
                    "0": {
                    "imageUrl": "https://storage.googleapis.com/janelia-spark-test/hess_wafer_61_data/scan_046/slab_0145/mfov_0000/sfov_023.png",
                    "imageLoaderType": "IMAGEJ_DEFAULT_W_TIMEOUT"
                    }
                    },
                    "transforms": {
                    "type": "list",
                    "specList": [
                    {
                    "type": "leaf",
                    "className": "org.janelia.alignment.transform.ExponentialFunctionOffsetTransform",
                    "dataString": "3.164065083689898,0.010223592506552219,0.0,0"
                    },
                    {
                    "type": "leaf",
                    "className": "mpicbg.trakem2.transform.AffineModel2D",
                    "dataString": "1 0 0 1 14296.41339989654 31631.33097979614"
                    }
                    ]
                    },
                    "meshCellSize": 64
                    }""";

    private static final String Q_TILE_SPEC_JSON = """
                    {
                    "tileId": "w61_magc0145_scan046_m0000_r20_s41",
                    "layout": {
                    "sectionId": "42.0",
                    "imageRow": 33,
                    "imageCol": 15,
                    "stageX": -68411.98666969372,
                    "stageY": -40520.934959339866
                    },
                    "z": 42,
                    "minX": 16179,
                    "minY": 31628,
                    "maxX": 18183,
                    "maxY": 33376,
                    "width": 2000,
                    "height": 1748,
                    "minIntensity": 0,
                    "maxIntensity": 255,
                    "mipmapLevels": {
                    "0": {
                    "imageUrl": "https://storage.googleapis.com/janelia-spark-test/hess_wafer_61_data/scan_046/slab_0145/mfov_0000/sfov_041.png",
                    "imageLoaderType": "IMAGEJ_DEFAULT_W_TIMEOUT"
                    }
                    },
                    "transforms": {
                    "type": "list",
                    "specList": [
                    {
                    "type": "leaf",
                    "className": "org.janelia.alignment.transform.ExponentialFunctionOffsetTransform",
                    "dataString": "3.164065083689898,0.010223592506552219,0.0,0"
                    },
                    {
                    "type": "leaf",
                    "className": "mpicbg.trakem2.transform.AffineModel2D",
                    "dataString": "1 0 0 1 16182.580711159244 31628.476101925553"
                    }
                    ]
                    },
                    "meshCellSize": 64
                    }""";
}
