package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Util;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for debugging transformed corners of IBEAM-MSEM stacks.
 *
 * @author Eric Trautman
 */
public class DebugTransformedCornersClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stacks to process",
                variableArity = true,
                required = true)
        public List<String> stackNames;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles",
                required = true
        )
        public Double xyNeighborFactor;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value",
                required = true
        )
        public Integer zNeighborDistance;

        @Parameter(
                names = "--tileId",
                description = "Only debug pairs that include these tileIds",
                variableArity = true
        )
        public List<String> tileIds;

        @Parameter(
                names = "--tileIdPattern",
                description = "Only debug pairs that include these tileIds that match this pattern"
        )
        public String tileIdPattern;

        @Parameter(
                names = "--tileId2",
                description = "Only debug pairs that contain this String"
        )
        public String tileId2;

        public Parameters() {
        }

        public boolean hasTileIds() {
            return (tileIds != null) && (tileIds.size() > 0);
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final DebugTransformedCornersClient client = new DebugTransformedCornersClient(parameters);
                client.debugPairs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final List<Double> zValues;
    private final Set<String> tileIds;
    private final Pattern tileIdPattern;

    DebugTransformedCornersClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();

        final String firstStackName = parameters.stackNames.get(0);

        final Set<Double> explicitZValues = new HashSet<>();
        if (parameters.hasTileIds()) {

            this.tileIds = new HashSet<>(parameters.tileIds);
            for (final String tileId : this.tileIds) {
                final Double tileZ = renderDataClient.getTile(firstStackName, tileId).getZ();
                final double maxZ = tileZ + parameters.zNeighborDistance + 1.0;
                for (double z = tileZ - parameters.zNeighborDistance; z < maxZ; z++) {
                    explicitZValues.add(z);
                }
            }

        } else {
            this.tileIds = null;
        }

        this.zValues = renderDataClient.getStackZValues(firstStackName,
                                                        parameters.layerRange.minZ,
                                                        parameters.layerRange.maxZ,
                                                        explicitZValues);
        if (this.zValues.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + firstStackName + " does not contain any layers with the specified z values, " +
                    "confirm --minZ and --maxZ are correct");
        } else if (this.zValues.size() > 100) {
            throw new IllegalArgumentException(
                    this.zValues.size() + " z layers were found, tool is currently limited to a max of 100 z layers");
        }

        Collections.sort(this.zValues);

        if (parameters.tileIdPattern != null) {
            if (parameters.hasTileIds()) {
                throw new IllegalArgumentException("specify either --tileId or --tileIdPattern but not both");
            }
            this.tileIdPattern = Pattern.compile(parameters.tileIdPattern);
        } else {
            this.tileIdPattern = null;
        }
    }

    private void debugPairs()
            throws IOException {
        final List<String> debugInfo = new ArrayList<>();
        for (final String stackName : parameters.stackNames) {
            debugInfo.addAll(debugPairsForStack(stackName));
        }
        LOG.info("debug results are:");
        debugInfo.stream().sorted().forEach(System.out::println);
    }

    private List<String> debugPairsForStack(final String stackName)
            throws IOException {

        LOG.info("debugPairsForStack: entry, stackName={}", stackName);

        final List<String> debugInfo = new ArrayList<>();

        final double maxZ = zValues.get(zValues.size() - 1);

        final Map<Double, TileBoundsRTree> zToTreeMap = new LinkedHashMap<>(zValues.size());

        // load the first zNeighborDistance trees
        double z;
        for (int zIndex = 0; (zIndex < zValues.size()) && (zIndex < parameters.zNeighborDistance); zIndex++) {
            z = zValues.get(zIndex);
            zToTreeMap.put(z, buildRTree(stackName, z));
        }

        final Set<OrderedCanvasIdPair> neighborPairs = new TreeSet<>();

        Double neighborZ;
        TileBoundsRTree currentZTree;
        List<TileBoundsRTree> neighborTreeList;
        Set<OrderedCanvasIdPair> currentNeighborPairs;
        for (int zIndex = 0; zIndex < zValues.size(); zIndex++) {

            z = zValues.get(zIndex);

            if ((parameters.zNeighborDistance == 0) || (! zToTreeMap.containsKey(z))) {
                zToTreeMap.put(z, buildRTree(stackName, z));
            }

            neighborTreeList = new ArrayList<>();

            final double idealMaxNeighborZ = Math.min(maxZ, z + parameters.zNeighborDistance);
            for (int neighborZIndex = zIndex + 1; neighborZIndex < zValues.size(); neighborZIndex++) {

                neighborZ = zValues.get(neighborZIndex);

                if (neighborZ > idealMaxNeighborZ) {
                    break;
                }

                if (! zToTreeMap.containsKey(neighborZ)) {
                    if (zIndex > 0) {
                        final double completedZ = zValues.get(zIndex - 1);
                        zToTreeMap.remove(completedZ);
                    }
                    zToTreeMap.put(neighborZ, buildRTree(stackName, neighborZ));
                }

                neighborTreeList.add(zToTreeMap.get(neighborZ));
            }

            currentZTree = zToTreeMap.get(z);

            final List<TileBounds> sourceTileBoundsList;
            if (tileIds == null) {
                if (tileIdPattern == null) {
                    sourceTileBoundsList = currentZTree.getTileBoundsList();
                } else {
                    sourceTileBoundsList = currentZTree.getTileBoundsList().stream()
                            .filter(tb -> tileIdPattern.matcher(tb.getTileId()).matches())
                            .collect(Collectors.toList());
                }
            } else {
                sourceTileBoundsList = currentZTree.getTileBoundsList().stream()
                        .filter(tb -> tileIds.contains(tb.getTileId()))
                        .collect(Collectors.toList());
            }

            currentNeighborPairs = currentZTree.getCircleNeighbors(sourceTileBoundsList,
                                                                   neighborTreeList,
                                                                   parameters.xyNeighborFactor,
                                                                   null,
                                                                   false,
                                                                   false,
                                                                   false);

            neighborPairs.addAll(currentNeighborPairs);
        }

        // compute a model across all corner points
        final List<Point> pTransformedCornersAll = new ArrayList<>();
        final List<Point> qTransformedCornersAll = new ArrayList<>();
        
        if (neighborPairs.size() > 0) {
            final Map<Double, ResolvedTileSpecCollection> zToTilesMap = new HashMap<>(zValues.size());
            for (final Double zVal : zValues) {
                zToTilesMap.put(zVal, renderDataClient.getResolvedTiles(stackName, zVal));
            }

            neighborPairs.stream().sorted().forEach(pair -> {
                final CanvasId pCanvasId = pair.getP();
                final CanvasId qCanvasId = pair.getQ();
                final Double pz = Double.valueOf(pCanvasId.getGroupId()); // hack: assumes no z reordering
                final Double qz = Double.valueOf(qCanvasId.getGroupId()); // hack: assumes no z reordering
                final TileSpec pTileSpec = zToTilesMap.get(pz).getTileSpec(pCanvasId.getId());
                final TileSpec qTileSpec =  zToTilesMap.get(qz).getTileSpec(qCanvasId.getId());
                if (tileIds == null ||
                    tileIds.contains(pTileSpec.getTileId()) ||
                    tileIds.contains(qTileSpec.getTileId()))
                {
                	
                	if ( parameters.tileId2 == null || pTileSpec.getTileId().contains(parameters.tileId2) || qTileSpec.getTileId().contains(parameters.tileId2) ) 
                	{
                	//System.out.println( pTileSpec.getTileId() + "  " + qTileSpec.getTileId() );//001_000003_045
                		debugInfo.add(formatCornerPointDistances(stackName, pTileSpec, qTileSpec));


                        // find a model that maps the p corners to metadata p corners and apply it to q corners as well
                        // because we only want a relative model
                        {
                        	final List<Point> p0 = getUnTransformedCornerPoints(pTileSpec);

                            //for ( final Point p : p0 )
                            //	System.out.println( "l=" + Util.printCoordinates(p.getL()) + ", w=" + Util.printCoordinates(p.getW()));

                            final List<Point> pC = getTransformedCornerPoints(pTileSpec);
                            final List<Point> qC = getTransformedCornerPoints(qTileSpec);

                            final ArrayList< PointMatch > matches = new ArrayList<>();
                            for ( int i = 0; i < p0.size(); ++i )
                            	matches.add( new PointMatch(pC.get( i ), p0.get( i )));

                            final AffineModel2D model = new AffineModel2D();
                            try {
                            	model.fit( matches );

                    			for ( final PointMatch pm : matches )
                    				pm.apply( model );

                    			// apply the model to pCorners and qCorners so we only keep a relative model
                    			// so pC will be identical to p0
                    			for ( int i = 0; i < p0.size(); ++i )
                    			{
                    				pTransformedCornersAll.add(new Point(pC.get(i).getW()));
                    				qC.get( i ).apply( model );
                    				qTransformedCornersAll.add(new Point(qC.get(i).getW()));

                                	//System.out.println( "p=" + Util.printCoordinates(pTransformedCornersAll.get(i).getL()) + ", q=" + Util.printCoordinates(qTransformedCornersAll.get(i).getL()));
                    			}
                    			//System.out.println( model + ", " + model.getClass().getSimpleName());
                    			//System.exit( 0 );
                    		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
                    			e.printStackTrace();
                    		}
                        }

                		// l and w are transformed
                        final List<Point> pTransformedCorners = getTransformedCornerPoints(pTileSpec);
                        final List<Point> qTransformedCorners = getTransformedCornerPoints(qTileSpec);

                        //for ( final Point p : pTransformedCorners )
                        //	System.out.println( "l=" + Util.printCoordinates(p.getL()) + ", w=" + Util.printCoordinates(p.getW()));

                        // the model tells us how much the translation changes,
                        // the error tells us how affine it is
                        computeError(pTransformedCorners, qTransformedCorners, new TranslationModel2D() );
                        //computeError(pTransformedCorners, qTransformedCorners, new RigidModel2D() );
                	}
                }
            });
        }

        System.out.println( );

        //computeError(pTransformedCornersAll, qTransformedCornersAll, new TranslationModel2D() );
        //computeError(pTransformedCornersAll, qTransformedCornersAll, new RigidModel2D() );
        computeError(pTransformedCornersAll, qTransformedCornersAll, new AffineModel2D() );


        System.exit( 0 );

        return debugInfo;
    }

    public static void computeError( final List<Point> p, final List<Point> q, final Model<?> model )
    {
        final ArrayList< PointMatch > matches = new ArrayList<>();
        for ( int i = 0; i < p.size(); ++i )
        	matches.add( new PointMatch(p.get( i ), q.get( i )));

        //final TranslationModel2D t = new TranslationModel2D();
        try {
        	model.fit( matches );
			double error = 0;
			double maxError = 0;
			
			for ( final PointMatch pm : matches )
			{
				pm.apply( model );
				error += pm.getDistance();
				maxError = Math.max( maxError, pm.getDistance() );
			}

			error /= matches.size();

			model.setCost( error );
			System.out.println( "maxE=" + maxError + " " + model + ", " + model.getClass().getSimpleName());

		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    }
   
    public TileBoundsRTree buildRTree(final String stackName,
                                      final double z)
            throws IOException {
        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stackName, z);
        return new TileBoundsRTree(z, tileBoundsList);
    }

    public static List<Point> getTransformedCornerPoints(final TileSpec tileSpec) {

        final List<Point> transformedCornerPoints = new ArrayList<>();

        final double[][] rawCornerLocations = {
            {                   0,                    0 },
            { tileSpec.getWidth(),                    0 },
            {                   0, tileSpec.getHeight() },
            { tileSpec.getWidth(), tileSpec.getHeight() }
        };

        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        for (final double[] rawCornerLocation : rawCornerLocations)  {
            transformedCornerPoints.add(new Point(transformList.apply(rawCornerLocation)));
        }

        return transformedCornerPoints;
    }

    public static List<Point> getUnTransformedCornerPoints(final TileSpec tileSpec) {

        final List<Point> transformedCornerPoints = new ArrayList<>();

        final double[][] rawCornerLocations = {
            {                   0,                    0 },
            { tileSpec.getWidth(),                    0 },
            {                   0, tileSpec.getHeight() },
            { tileSpec.getWidth(), tileSpec.getHeight() }
        };

        for (final double[] rawCornerLocation : rawCornerLocations)  {
            transformedCornerPoints.add(new Point(rawCornerLocation));
        }

        return transformedCornerPoints;
    }

    public static String formatCornerPointDistances(final String stack,
                                                    final TileSpec pTileSpec,
                                                    final TileSpec qTileSpec) {

        final List<Point> pTransformedCorners = getTransformedCornerPoints(pTileSpec);
        final List<Point> qTransformedCorners = getTransformedCornerPoints(qTileSpec);

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pTransformedCorners.size(); i++) {
            final double distance = Point.distance(pTransformedCorners.get(i), qTransformedCorners.get(i));
            sb.append(String.format("%8.1f", distance));
        }

        return String.format("%40s to %40s in %-40s= %s", pTileSpec.getTileId(), qTileSpec.getTileId(), stack, sb);
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(DebugTransformedCornersClient.class);
}
