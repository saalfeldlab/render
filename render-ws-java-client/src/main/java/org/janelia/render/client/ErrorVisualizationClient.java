package org.janelia.render.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImageJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * Java client example for Stephan Preibisch to start developing visualization tools for match data.
 *
 * @author Eric Trautman
 */
public class ErrorVisualizationClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @ParametersDelegate
        public LayerBoundsParameters bounds = new LayerBoundsParameters();

        @Parameter(
                names = "--matchOwner",
                description = "Match owner (default is same as owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Match collection",
                required = true)
        public String matchCollection;

        public Parameters() {
        }

        String getMatchOwner() {
            return matchOwner == null ? renderWeb.owner : matchOwner;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.bounds.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final ErrorVisualizationClient client = new ErrorVisualizationClient(parameters);

                client.printConnections();
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;

    private final Map<Double, List<String>> zToSectionIdMap;

    /**
     * Basic constructor.
     *
     * @param  parameters  parameters for this client.
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid.
     *
     * @throws IOException
     *   if there are any problems retrieving data from the render web services.
     */
    ErrorVisualizationClient(final Parameters parameters)
            throws IllegalArgumentException, IOException {

        this.parameters = parameters;

        // utility to submit HTTP requests to the render web service for render (tile and transform) data
        this.renderDataClient = parameters.renderWeb.getDataClient();

        // utility to submit HTTP requests to the render web service for match data
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.getMatchOwner(),
                                                    parameters.matchCollection);

        // A tile's sectionId is a logically immutable string value that typically corresponds to
        // the acquisition system's estimate of the tile's z position in the stack.
        // A tile's z value is the aligned double z position of the tile in the stack.
        // In most cases, z is simply the parsed double form of a tile's string sectionId.
        // However, tiles in reordered layers will have a z that differs from the tile's sectionId.
        // Reacquired tiles also have differing sectionId values (typically <z>.1, <z>.2, ...).

        // Point matches are stored using the tile's immutable sectionId (as groupId) and tileId (as id)
        // so that we can reorder tile layers without needing to change/update previously stored match data.

        // retrieve the list of sections for the stack (optionally filtered by a range)
        final List<SectionData> stackSectionDataList =
                renderDataClient.getStackSectionData(parameters.stack,
                                                     parameters.layerRange.minZ,
                                                     parameters.layerRange.maxZ);

        // map sections to z values (usually this is one-to-one, but it may not be)
        this.zToSectionIdMap = new HashMap<>(stackSectionDataList.size());
        for (final SectionData sectionData : stackSectionDataList) {
            final List<String> sectionIdList = zToSectionIdMap.computeIfAbsent(sectionData.getZ(),
                                                                               z -> new ArrayList<>());
            sectionIdList.add(sectionData.getSectionId());
        }

    }

    void printConnections()
            throws IllegalArgumentException, IOException {

        final List<Double> zValues = zToSectionIdMap.keySet().stream().sorted().collect(Collectors.toList());

        if (zValues.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any layers with the specified z values");
        }

        final ArrayList< ArrayList< String > > tileIdStore = new ArrayList<>();
        final ArrayList< ArrayList< Pair< Integer, Integer > > > loc = new ArrayList<>(); // x tile location, z tile location
        final ArrayList< ArrayList< Pair< Double, Double > > > realLoc = new ArrayList<>(); // x tile real min, x tile max
        final ArrayList< ArrayList< Double > > avgError = new ArrayList<>();
        final ArrayList< ArrayList< Double > > maxError = new ArrayList<>();
        final ArrayList< ArrayList< Pair< Integer, Integer > > > maxErrorLoc = new ArrayList<>(); // worst tile x location, worst tile z location

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles( parameters.stack, zValues.get( 0 ), zValues.get( zValues.size() - 1 ) + 6, null, null, null, null, null );
        
        // TODO: Loop
        for ( int i = 0; i < zValues.size(); ++i )
        {
	        final double currentZ = zValues.get( i );
	        System.out.println( "---CURRENT-Z =" + currentZ );
	
	        tileIdStore.add( new ArrayList<>() );
	        loc.add( new ArrayList<>() );
	        realLoc.add( new ArrayList<>() );
	        avgError.add( new ArrayList<>() );
	        maxError.add( new ArrayList<>() );
	        maxErrorLoc.add( new ArrayList<>() );
	
	        // all connected pairs that contain p-tiles from layer z
	        final List<CanvasMatches> cmatches = getMatchedPairsForLayer(currentZ);

	        // maps tileId to TileSpec and has transformations
	        //final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles( parameters.stack, currentZ, currentZ+6, null, null, null, null, null );
	
	        // map tileId to CanvasMatches
	        final Map< String, List< CanvasMatches> > matchMap = new HashMap<>();
	
	        for ( final CanvasMatches cm : cmatches )
	        {
	        	final String tileId = cm.getpId();
	        	if ( resolvedTiles.getTileSpec( tileId ) == null )
	        		continue;
	
	        	if ( matchMap.containsKey( tileId ) )
	        	{
	        		matchMap.get( tileId ).add( cm );
	        	}
	        	else
	        	{
	        		final List< CanvasMatches > tmp = new ArrayList<>();
	        		tmp.add( cm );
	        		matchMap.put( tileId, tmp );
	        	}
	        }

	        // sort by tileId
	        final ArrayList< String > tileIds = new ArrayList<>();
	        tileIds.addAll( matchMap.keySet() );
	        Collections.sort( tileIds );
	
	        // for each tileid do
	        final double pGlobal[] = new double[ 2 ];
	        final double qGlobal[] = new double[ 2 ];
	
	        final double pLocal[] = new double[ 2 ];
	        final double qLocal[] = new double[ 2 ];
	
	        // parsed location in x and z
	        final int[] tmpLoc = new int[ 2 ];
	
	        // for local fits
	        final InterpolatedAffineModel2D< AffineModel2D, RigidModel2D > crossLayerModel = new InterpolatedAffineModel2D<>( new AffineModel2D(), new RigidModel2D(), 0.25 );
	        final InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D > montageLayerModel = new InterpolatedAffineModel2D<>( new RigidModel2D(), new TranslationModel2D(), 0.25 );
	  
	        for ( final String tileId : tileIds )
	        {
	        	getTilePositions( tileId, tmpLoc );
	
	        	final int xPos = tmpLoc[ 0 ];
	        	final int zPos = tmpLoc[ 1 ];
	
	        	System.out.println( "\ncurrent tile: " + tileId + " [x=" + xPos + ",z=" + zPos + "]" );
	
	        	double avgDiff = 0;
	        	int countDiff = 0;
	        	double maxDiff = -1;
	        	TileSpec maxTileSpec = null;
	
	        	for ( final CanvasMatches cm : matchMap.get( tileId ) )
		        {
			        	// could be null if some tiles were removed (resin tiles)
			        	final TileSpec pTileSpec = resolvedTiles.getTileSpec( cm.getpId() );
			        	final TileSpec qTileSpec = resolvedTiles.getTileSpec( cm.getqId() );
		
			        	if ( qTileSpec == null )
			        		continue; //System.out.println( "non-existent tile" );
		
			        	System.out.println( " >>> " + qTileSpec.getTileId() );
		
			        	// size of tiles (apply to the center)
			        	pGlobal[ 0 ] = pLocal[ 0 ] = pTileSpec.getWidth() / 2;
			        	pGlobal[ 1 ] = pLocal[ 1 ] = pTileSpec.getHeight() / 2;
		
			        	qGlobal[ 0 ] = qLocal[ 0 ] = qTileSpec.getWidth() / 2;
			        	qGlobal[ 1 ] = qLocal[ 1 ] = qTileSpec.getHeight() / 2;
		
			        	// global models (when calling it after running a solve)
			        	//System.out.print( Util.printCoordinates( pGlobal ) + ", " + Util.printCoordinates( qGlobal ) + " >>> ");
			        	pTileSpec.getTransformList().applyInPlace( pGlobal );
			        	qTileSpec.getTransformList().applyInPlace( qGlobal );
			        	//System.out.println( Util.printCoordinates( pGlobal ) + ", " + Util.printCoordinates( qGlobal ) );
		
			        	// the actual matches, local solve
			        	cm.getMatches().getPList();
			        	cm.getMatches().getQList();
			        	final List< PointMatch > pms = CanvasMatchResult.convertMatchesToPointMatchList( cm.getMatches() );
			        	
			        	final InterpolatedAffineModel2D< ?,? > model;
		
			        	if ( pTileSpec.getZ().doubleValue() == qTileSpec.getZ().doubleValue() )
			        		model = montageLayerModel;
			        	else
			        		model = crossLayerModel;
		
			        	try
						{
							model.fit( pms );
						}
			        	catch ( Exception e )
						{
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
			        	model.applyInPlace( pLocal );
			        	
			        	// vectors
			        	pGlobal[ 0 ] = qGlobal[ 0 ] - pGlobal[ 0 ];
			        	pGlobal[ 1 ] = qGlobal[ 1 ] - pGlobal[ 1 ];
		
			        	pLocal[ 0 ] = qLocal[ 0 ] - pLocal[ 0 ];
			        	pLocal[ 1 ] = qLocal[ 1 ] - pLocal[ 1 ];
		
			        	//System.out.println( "vGlobal: " + Util.printCoordinates( pGlobal ) );
			        	//System.out.println( "vLocal: " + Util.printCoordinates( pLocal ) );
		
			        	final double vDiff = distance( pLocal[ 0 ], pLocal[ 1 ], pGlobal[ 0 ], pGlobal[ 1 ] );
			        	//System.out.println( "diff: " + vDiff );
		
			        	avgDiff += vDiff;
			        	++countDiff;
		
			        	if ( vDiff > maxDiff )
			        	{
			        		maxDiff = vDiff;
			        		maxTileSpec = qTileSpec;
			        	}
		        }
	
	        	int maxLocX = 0;
	        	int maxLocZ = 0;
	
	        	if ( countDiff > 0 )
	        	{
	        		avgDiff /= (double)countDiff;
	        		getTilePositions( maxTileSpec.getTileId(), tmpLoc );
	        		maxLocX = tmpLoc[ 0 ];
	        		maxLocZ = tmpLoc[ 1 ];
	        	}
	        	else
	        	{
	        		//unconnected
	        		avgDiff = 0;
	        		maxDiff = 0;
	        	}
	
	        	System.out.println( "avg_diff: " + avgDiff );
	        	System.out.println( "max_diff: " + maxDiff + " to tile: [x=" + maxLocX + ",z=" + maxLocZ + "]" );
	
	        	// figure out real location of tile in x
	        	final TileSpec tileSpec = resolvedTiles.getTileSpec( tileId );
	        	pGlobal[ 0 ] = 0;
	        	pGlobal[ 1 ] = tileSpec.getHeight() / 2;
	        	tileSpec.getTransformList().applyInPlace( pGlobal );
	        	final double xStart = pGlobal[ 0 ];

	        	pGlobal[ 0 ] = tileSpec.getWidth() - 1;
	        	pGlobal[ 1 ] = tileSpec.getHeight() / 2;
	        	tileSpec.getTransformList().applyInPlace( pGlobal );
	        	final double xEnd = pGlobal[ 0 ];
	        	
	        	loc.get( loc.size() - 1 ).add( new ValuePair<>( xPos, zPos ) );
	        	realLoc.get( realLoc.size() - 1 ).add( new ValuePair<>( xStart, xEnd ) );
	        	tileIdStore.get( tileIdStore.size() - 1 ).add( tileId );
	        	avgError.get( avgError.size() - 1 ).add( avgDiff );
	        	maxError.get( maxError.size() - 1 ).add( maxDiff );
	        	maxErrorLoc.get( maxErrorLoc.size() - 1 ).add( new ValuePair<>( maxLocX, maxLocZ ) );
	        }

        }
        
        render( loc, realLoc, avgError, maxError, maxErrorLoc, 100.0 );
    }

    public static void render(
    		final ArrayList< ArrayList< Pair< Integer, Integer > > > loc,
    		final ArrayList< ArrayList< Pair< Double, Double > > > realLoc,
    		final ArrayList< ArrayList< Double > > avgError,
            final ArrayList< ArrayList< Double > > maxError,
            final ArrayList< ArrayList< Pair< Integer, Integer > > > maxErrorLoc,
            final double scaleX )
    {
    	int minX = loc.get( 0 ).get( 0 ).getA();
    	int maxX = minX;

    	double realMinX = realLoc.get( 0 ).get( 0 ).getA();
    	double realMaxX = realMinX;

    	int minZ = loc.get( 0 ).get( 0 ).getB();
    	int maxZ = minZ;

    	int prevZ = -1;

    	boolean isContinousX = true;

    	boolean isContinousZ = true;
    	boolean isConsistentZ = true;

    	for ( int j = 0; j < loc.size(); ++j )
    	{
        	final ArrayList< Pair< Double, Double > > lineRealLoc = realLoc.get( j );
        	final ArrayList< Pair< Integer, Integer > > lineLoc = loc.get( j );

        	double rX0 = lineRealLoc.get( 0 ).getA();
        	double rX1 = lineRealLoc.get( 0 ).getB();

        	realMinX = Math.min( Math.min( rX0, rX1 ), realMinX );
        	realMaxX = Math.max( Math.max( rX0, rX1 ), realMaxX );

    		int x = lineLoc.get( 0 ).getA();
    		int z = lineLoc.get( 0 ).getB();

    		minX = Math.min( x, minX );
    		maxX = Math.max( x, maxX );
    		minZ = Math.min( z, minZ );
    		maxZ = Math.max( z, maxZ );
    		
    		if ( prevZ != -1 && z != prevZ + 1 )
    			isContinousZ = false;

    		prevZ = z;

    		if ( x != 0 )
    			isContinousX = false;

    		for ( int i = 1; i < lineLoc.size(); ++i )
    		{
    			if ( lineLoc.get( i ).getB() != z )
    				isConsistentZ = false;

    			if ( lineLoc.get( i ).getA() != lineLoc.get( i - 1 ).getA() + 1 )
    				isContinousX = false;

        		minX = Math.min( lineLoc.get( i ).getA(), minX );
        		minZ = Math.min( lineLoc.get( i ).getB(), minZ );
        		maxX = Math.max( lineLoc.get( i ).getA(), maxX );
        		maxZ = Math.max( lineLoc.get( i ).getB(), maxZ );

            	realMinX = Math.min( Math.min( lineRealLoc.get( i ).getA(), lineRealLoc.get( i ).getB() ), realMinX );
            	realMaxX = Math.max( Math.max( lineRealLoc.get( i ).getA(), lineRealLoc.get( i ).getB() ), realMaxX );
    		}
    	}

    	System.out.println( "minX: " + minX );
    	System.out.println( "maxX: " + maxX );
    	System.out.println( "minZ: " + minZ );
    	System.out.println( "maxZ: " + maxZ );
    	System.out.println( "realMinX: " + realMinX );
    	System.out.println( "realMaxX: " + realMaxX );
    	System.out.println( "isContinousX: " + isContinousX );
    	System.out.println( "isContinousZ: " + isContinousZ );
    	System.out.println( "isConsistentZ: " + isConsistentZ );

		final int width = (int)Math.round( (realMaxX - realMinX) / scaleX ) + 1;

		final FloatProcessor avg = new FloatProcessor( width, maxZ - minZ + 1 );
		final FloatProcessor max = new FloatProcessor( width, maxZ - minZ + 1 );
		final FloatProcessor maxLx = new FloatProcessor( width, maxZ - minZ + 1 );
		final FloatProcessor maxLz = new FloatProcessor( width, maxZ - minZ + 1 );
		final FloatProcessor lx = new FloatProcessor( width, maxZ - minZ + 1 );

		setTo( avg, -1 );
		setTo( max, -1 );
		setTo( maxLx, -1 );
		setTo( maxLz, -1 );
		setTo( lx, -1 );

    	for ( int z = 0; z < loc.size(); ++z )
    	{
    		final ArrayList< Pair< Integer, Integer > > line = loc.get( z );

    		for ( int x = 0; x < line.size(); ++x )
    		{
    			final float avgValue = avgError.get( z ).get( x ).floatValue();
    			final float maxValue = maxError.get( z ).get( x ).floatValue();
    			final int maxLocX = maxErrorLoc.get( z ).get( x ).getA();
    			final int maxLocZ = maxErrorLoc.get( z ).get( x ).getB();

    			final Pair< Integer, Integer > tile = line.get( x );
    			final int zp = tile.getB();

    			final Pair< Double, Double > tileReal = realLoc.get( z ).get( x );
    			final int fromX = (int)Math.round( (tileReal.getA() - realMinX) / scaleX );
    			final int toX = (int)Math.round( (tileReal.getB() - realMinX) / scaleX );
    			final int xIndex = tile.getA();

    			for ( int xp = fromX; xp < toX; ++xp )
    			{
    				avg.setf( xp - minX, zp - minZ, avgValue );
    				max.setf( xp - minX, zp - minZ, maxValue );
    				maxLx.setf( xp - minX, zp - minZ, maxLocX );
    				maxLz.setf( xp - minX, zp - minZ, maxLocZ );
    				lx.setf( xp - minX, zp - minZ, xIndex );
    			}
    		}
    	}

    	new ImageJ();
    	final ImagePlus impAvg = new ImagePlus( "avgError", avg );
    	final ImagePlus impMax = new ImagePlus( "maxError", max );
    	final ImagePlus impMaxLocX = new ImagePlus( "maxLocX", maxLx );
    	final ImagePlus impMaxLocZ = new ImagePlus( "maxLocZ", maxLz );
    	final ImagePlus impLX = new ImagePlus( "lx", lx );

    	final Calibration cal = impAvg.getCalibration();
    	cal.yOrigin = -minZ;
    	cal.xOrigin = -realMinX/scaleX;
    	cal.pixelWidth = scaleX;
    	impAvg.setCalibration( cal );
    	impMax.setCalibration( cal );
    	impMaxLocX.setCalibration( cal );
    	impMaxLocZ.setCalibration( cal );
    	impLX.setCalibration( cal );

    	impMaxLocX.setDisplayRange( -1, maxX );
    	impMaxLocZ.setDisplayRange( minZ, maxZ );
    	impLX.setDisplayRange( -1, maxX );

    	impAvg.show();
    	impMax.show();
    	impMaxLocX.show();
    	impMaxLocZ.show();
    	impLX.show();
    }

    private static final void setTo( final FloatProcessor fp, final float value )
    {
    	for ( int i = 0; i < fp.getPixelCount(); ++i )
    		fp.setf( i, value );
    }

    public static void getTilePositions( final String tileId, final int[] pos )
    {
    	final int indexX = tileId.indexOf( "_0-0-" );

    	if ( indexX == -1 )
    		throw new RuntimeException( "Cannot identify x position for tileid: " + tileId );

    	final int indexZ1 = tileId.indexOf( ".", indexX );

    	if ( indexZ1 == -1 )
    		throw new RuntimeException( "Cannot identify z position for tileid (z1): " + tileId );

    	final int indexZ2 = tileId.indexOf( ".", indexZ1 + 1 );

    	if ( indexZ2 == -1 )
    		throw new RuntimeException( "Cannot identify z position for tileid (z2): " + tileId );

    	final String xPos = tileId.substring( indexX + 5, indexZ1 );
    	final String zPos = tileId.substring( indexZ1 + 1, indexZ2 );

    	/*
    	System.out.println( tileId );
    	System.out.println( indexX );
    	System.out.println( indexZ1 );
    	System.out.println( indexZ2 );
    	System.out.println( xPos );
    	System.out.println( zPos );
		*/

    	final int x = Integer.parseInt( xPos );
    	final int z = Integer.parseInt( zPos );
    	pos[ 0 ] = x;
    	pos[ 1 ] = z;
    }

	final static public double distance( final double px, final double py, final double qx, final double qy )
	{
		double sum = 0.0;
		
		final double dx = px - qx;
		sum += dx * dx;

		final double dy = py - qy;
		sum += dy * dy;

		return Math.sqrt( sum );
	}

    // Fetches match data.
    private List< CanvasMatches > getMatchedPairsForLayer(final double z)
            throws IOException {

        final List<CanvasMatches> pairs = new ArrayList<>();

        final List<String> groupIds = zToSectionIdMap.get(z);

        // We only need connection information (not specific match points),
        // so excluding match details is sufficient and will make requests much faster.
        final boolean excludeMatchDetails = false; //GET THE POINTS

        if (groupIds != null) {

            // Typically groupId is the same as z, but it can be different (see long explanation above).
            for (final String pGroupId : groupIds) {
            	pairs.addAll( matchDataClient.getMatchesWithPGroupId(pGroupId,excludeMatchDetails) );
                }
        }
        return pairs;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ErrorVisualizationClient.class);

}
