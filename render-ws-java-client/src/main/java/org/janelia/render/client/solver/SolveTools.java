package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.util.ScriptUtil;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.minlog.Log;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.IdentityModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class SolveTools
{
	private SolveTools() {}

	public static double computeAlignmentError(
			final Model< ? > crossLayerModel,
			final Model< ? > montageLayerModel,
			final TileSpec pTileSpec,
			final TileSpec qTileSpec,
			final Model< ? > pAlignmentModel, // solveItem.idToNewModel().get( pTileId ), // p
			final Model< ? > qAlignmentModel, // solveItem.idToNewModel().get( qTileId ) ); // q
			final Matches matches )
	{
		return computeAlignmentError( crossLayerModel, montageLayerModel, pTileSpec, qTileSpec, pAlignmentModel, qAlignmentModel, matches, 10 );
	}

	public static double computeAlignmentError(
			final Model< ? > crossLayerModel,
			final Model< ? > montageLayerModel,
			final TileSpec pTileSpec,
			final TileSpec qTileSpec,
			final CoordinateTransform pAlignmentTransform,
			final CoordinateTransform qAlignmentTransform,
			final Matches matches,
			final int samplesPerDimension )
	{
		// for fitting local to global pair

		// the actual matches, local solve
		final List< PointMatch > pms = CanvasMatchResult.convertMatchesToPointMatchList( matches );
		final Model< ? > model;
		if (pTileSpec.getZ().intValue() == qTileSpec.getZ().intValue())
			model = montageLayerModel;
		else
			model = crossLayerModel;

		try {
			model.fit(pms);
		} catch (final Exception e) {
			LOG.info("Could not fit point matches", e);
		}

		// match the local solve to the global solve rigidly, as the entire stack is often slightly rotated
		// but do not change the transformations relative to each other (in local, global)
		final List< PointMatch > local = SolveTools.createFakeMatches(
				pTileSpec.getWidth(),
				pTileSpec.getHeight(),
				model,
				new IdentityModel(),
				samplesPerDimension);

		final List< PointMatch > global = SolveTools.createFakeMatches(
				pTileSpec.getWidth(),
				pTileSpec.getHeight(),
				pAlignmentTransform,
				qAlignmentTransform,
				samplesPerDimension);

		final Model<?> relativeModel = new RigidModel2D();
		final ArrayList< PointMatch > relativeMatches = new ArrayList<>();
		for ( int i = 0; i < global.size(); ++i )
		{
			relativeMatches.add( new PointMatch( new Point( local.get( i ).getP1().getL().clone() ), new Point( global.get( i ).getP1().getL().clone() ) ) );
			relativeMatches.add( new PointMatch( new Point( local.get( i ).getP2().getL().clone() ), new Point( global.get( i ).getP2().getL().clone() ) ) );
		}

		try {
			relativeModel.fit(relativeMatches);
		} catch (final Exception ignored){}

		double vDiff = 0;
		for ( int i = 0; i < global.size(); ++i )
		{
			final double dGx = global.get( i ).getP2().getL()[ 0 ] - global.get( i ).getP1().getL()[ 0 ];
			final double dGy = global.get( i ).getP2().getL()[ 1 ] - global.get( i ).getP1().getL()[ 1 ];

			final Point l1 = local.get( i ).getP1();
			final Point l2 = local.get( i ).getP2();

			l1.apply( relativeModel );
			l2.apply( relativeModel );

			final double dLx = l2.getW()[ 0 ] - l1.getW()[ 0 ];
			final double dLy = l2.getW()[ 1 ] - l1.getW()[ 1 ];

			vDiff += SolveTools.distance( dLx, dLy, dGx, dGy );

			//vDiff += SolveTools.distance( l1.getW()[ 0 ], l1.getW()[ 1 ], global.get( i ).getP1().getL()[ 0 ], global.get( i ).getP1().getL()[ 1 ] );
			//vDiff += SolveTools.distance( l2.getW()[ 0 ], l2.getW()[ 1 ], global.get( i ).getP2().getL()[ 0 ], global.get( i ).getP2().getL()[ 1 ] );
		}

		vDiff /= global.size();

		return vDiff;
	}

	public static double computeDifferenceToOptimalFit(
		final Model<?> model,
		final CoordinateTransform pAlignmentTransform,
		final CoordinateTransform qAlignmentTransform,
		final Matches matches
	) {
		// to a pairwise (=optimal) fit between the two tiles of a match
		final List<PointMatch> pointMatches = CanvasMatchResult.convertMatchesToPointMatchList(matches);
		try {
			model.fit(pointMatches);
		} catch (final Exception e) {
			LOG.info("Could not fit point matches", e);
			throw new RuntimeException(e);
		}

		// compare global to local fit
		double diff = 0;
		for (int i = 0; i < pointMatches.size(); ++i) {
			final PointMatch pm = pointMatches.get(i);

			final double[] p = pm.getP1().getL();
			// move p relative to q (which is locally fixed) and then move both to q's global position
			final double[] localPoint = qAlignmentTransform.apply(model.apply(p));
			// move p to its real global position as determined by the alignment model
			final double[] globalPoint = pAlignmentTransform.apply(p);

			diff += SolveTools.distance(localPoint[0], localPoint[1], globalPoint[0], globalPoint[1]);
		}

		diff /= pointMatches.size();

		return diff;
	}

	public static double computeRMSE(
			final CoordinateTransform pAlignmentTransform,
			final CoordinateTransform qAlignmentTransform,
			final Matches matches
	) {
		final List<PointMatch> pointMatches = CanvasMatchResult.convertMatchesToPointMatchList(matches);

		double error = 0;
		for (final PointMatch pm : pointMatches) {
			final double[] p = pAlignmentTransform.apply(pm.getP1().getL());
			final double[] q = qAlignmentTransform.apply(pm.getP2().getL());

			error += SolveTools.distance(p[0], p[1], q[0], q[1]);
		}

		error /= pointMatches.size();

		return error;
	}

	static public double distance(final double px, final double py, final double qx, final double qy)
	{
		double sum = 0.0;
		
		final double dx = px - qx;
		sum += dx * dx;

		final double dy = py - qy;
		sum += dy * dy;

		return Math.sqrt( sum );
	}

	public static List< PointMatch > createFakeMatches(
			final int w,
			final int h,
			final CoordinateTransform pTransform,
			final CoordinateTransform qTransform)
	{
		return createFakeMatches(w, h, pTransform, qTransform, SolveItem.samplesPerDimension);
	}

	public static List<PointMatch> createFakeMatches(
			final int w,
			final int h,
			final CoordinateTransform pTransform,
			final CoordinateTransform qTransform,
			final int samplesPerDimension)
	{
		final List< PointMatch > matches = new ArrayList<>();
		
		final double sampleWidth = (w - 1.0) / (samplesPerDimension - 1.0);
		final double sampleHeight = (h - 1.0) / (samplesPerDimension - 1.0);

		for (int y = 0; y < samplesPerDimension; ++y)
		{
			final double sampleY = y * sampleHeight;
			for (int x = 0; x < samplesPerDimension; ++x)
			{
				final double[] p = new double[] { x * sampleWidth, sampleY };
				final double[] q = new double[] { x * sampleWidth, sampleY };

				pTransform.applyInPlace(p);
				qTransform.applyInPlace(q);

				matches.add(new PointMatch( new Point(p), new Point(q) ));
			}
		}

		return matches;
	}

	protected static int fixIds( final List<? extends SolveItemData<?, ?, ?>> allItems, final int maxId )
	{
		final HashSet<Integer> existingIds = new HashSet<>();

		for ( final SolveItemData<?, ?, ?> item : allItems )
		{
			final int id = item.getId();

			if ( existingIds.contains( id ) )
			{
				// duplicate id
				if ( id <= maxId )
					throw new RuntimeException( "Id: " + id + " exists, but is <= maxId=" + maxId + ", this should never happen." );

				final int newId = Math.max( maxId, max( existingIds ) ) + 1;
				item.assignUpdatedId( newId );
				existingIds.add( newId );

				Log.info( "Assigning new id " + newId + " to block " + id);
			}
			else
			{
				Log.info( "Keeping id " + id);
				existingIds.add( id );
			}
		}

		return max( existingIds );
	}

	protected static int max( final Collection< Integer > ids )
	{
		int max = Integer.MIN_VALUE;

		for ( final int i : ids )
			max = Math.max( i, max );

		return max;
	}

	protected static <B extends Model<B> & Affine2D< B >> ArrayList< Pair< Pair< Integer, String>, Tile<B> > > layerDetails(
			final ArrayList< Integer > allZ,
			final HashMap<Integer, List<Tile<B>>> zToGroupedTileList,
			final SolveItem<?,B,?> solveItem,
			final int i )
	{
		final ArrayList< Pair< Pair< Integer, String>, Tile<B> > > prevTiles = new ArrayList<>();

		if ( i < 0 || i >= allZ.size() )
			return prevTiles;

		for ( final Tile< B > prevGroupedTile : zToGroupedTileList.get( allZ.get( i ) ) )
			for ( final Tile< B > imageTile : solveItem.groupedTileToTiles().get( prevGroupedTile ) )
			{
				final String tileId = solveItem.tileToIdMap().get( imageTile );
				final int tileCol = solveItem.idToTileSpec().get(tileId).getLayout().getImageCol();

				prevTiles.add( new ValuePair<>( new ValuePair<>( tileCol, tileId ), prevGroupedTile ) );
			}

		return prevTiles;
	}

	// also adds results to solveItem.zToDynamicLambda()
	protected static HashMap< Tile< ? >, Double > computeMetaDataLambdas(
			final Collection< Tile< ? > > tiles,
			final SolveItem< ?,?,? > solveItem,
			final int zRadiusRestarts,
			final Set<Integer> excludeFromRegularization,
			final double dynamicFactor )
	{
		// a z-section can have more than one grouped tile if they are connected from above and below
		final HashMap< Integer, List< Pair< Tile< ? >, Tile< TranslationModel2D > > > > zToTiles = fakePreAlign( tiles, solveItem );

		final ArrayList<Integer> allZ = new ArrayList<>(zToTiles.keySet());
		Collections.sort( allZ );

		final Img< DoubleType > valueX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > valueY = ArrayImgs.doubles( allZ.size() );

		RandomAccess< DoubleType > rX = valueX.randomAccess();
		RandomAccess< DoubleType > rY = valueY.randomAccess();
		
		for ( int z = 0; z < allZ.size(); ++ z )
		{
			final double[] offset = layerMinBounds( zToTiles.get( allZ.get( z ) ), solveItem);
			
			rX.setPosition( z, 0 );
			rY.setPosition( z, 0 );

			rX.get().set( offset[ 0 ] );
			rY.get().set( offset[ 1 ] );
		}

		//new ImageJ();
		//ImageJFunctions.show( valueX ).setTitle( "valueX" );
		//ImageJFunctions.show( valueY ).setTitle( "valueY" );

		final RandomAccess<DoubleType> rxIn = Views.extendMirrorDouble(valueX).randomAccess();
		final RandomAccess<DoubleType> ryIn = Views.extendMirrorDouble(valueY).randomAccess();

		final Img< DoubleType > derX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > derY = ArrayImgs.doubles( allZ.size() );

		final RandomAccess<DoubleType> rxOut = derX.randomAccess();
		final RandomAccess<DoubleType> ryOut = derY.randomAccess();

		for ( int z = 0; z < allZ.size(); ++z )
		{
			rxIn.setPosition( z - 1, 0 );
			ryIn.setPosition( z - 1, 0 );

			final double x = rxIn.get().get();
			final double y = ryIn.get().get();

			rxIn.setPosition( z + 1, 0 );
			ryIn.setPosition( z + 1, 0 );

			rxIn.fwd( 0 );
			ryIn.fwd( 0 );

			rxOut.setPosition( z, 0 );
			ryOut.setPosition( z, 0 );

			rxOut.get().set( Math.pow( x - rxIn.get().get(), 2 ) );
			ryOut.get().set( Math.pow( y - ryIn.get().get(), 2 ) );
		}

		//ImageJFunctions.show( derX ).setTitle( "derX" );
		//ImageJFunctions.show( derY ).setTitle( "derY" );

		final Img< DoubleType > filterX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > filterY = ArrayImgs.doubles( allZ.size() );

		Gauss3.gauss( 20, Views.extendMirrorDouble( derX ), filterX );
		Gauss3.gauss( 20, Views.extendMirrorDouble( derY ), filterY );

		//ImageJFunctions.show( filterX ).setTitle( "filterX" );
		//ImageJFunctions.show( filterY ).setTitle( "filterY" );

		rX = filterX.randomAccess();
		rY = filterY.randomAccess();
		
		for ( int i = 0; i < allZ.size(); ++i )
		{
			rX.setPosition( i, 0 );
			rY.setPosition( i, 0 );
		
			final double sum = (rX.get().get() + rY.get().get() );

			rY.get().set( sum );

			// the quadratic function is between f(0.0)=1 and f(114)=3.4293999999879254E-5
			final double lambda = Math.min( 1, Math.max( 0, sum < 115 ? ( 0.000076667*sum*sum - 0.017511667*sum + 1.0 ) * dynamicFactor : 3.4293999999879254E-5 * dynamicFactor ) );

			rX.get().set( lambda );
		}

		Gauss3.gauss( 5, Views.extendMirrorDouble( filterX ), filterX );

		final HashSet< Integer > exemptLayers = new HashSet<>();

		LOG.info( "Following restarts (+-z=" + zRadiusRestarts + ") will have lambda=0: " );

		for ( final int z : solveItem.restarts() )
		{
			LOG.info( "z=" + z );

			for ( int zR = z - zRadiusRestarts; zR <= z + zRadiusRestarts; ++zR )
				exemptLayers.add( zR );
		}

		LOG.info( "Following layers (arguments provided) have lambda=0: " );

		for ( final int z : excludeFromRegularization )
		{
			LOG.info( "z=" + z );

			exemptLayers.add( z );
		}

		final HashMap< Tile< ? >, Double > tileToDynamicLambda = new HashMap<>();

		LOG.info( "Lambdas:" );
		
		for ( int i = 0; i < allZ.size(); ++i )
		{
			final int z = allZ.get( i );

			final double lambda;

			if ( exemptLayers.contains( z ) )
			{
				lambda = 0;
			}
			else
			{
				rX.setPosition(new int[] { i } );
				lambda = rX.get().get();
			}

			solveItem.zToDynamicLambda().put( z, lambda );
			LOG.info( "z=" + z + ", lambda=" + lambda );

			for ( final Pair< Tile< ? >, Tile< TranslationModel2D > > tilePair : zToTiles.get( z ) )
				tileToDynamicLambda.put( tilePair.getA(), lambda );
		}

		//new ImageJ();
		//ImageJFunctions.show( filterX ).setTitle( "lambda" );
		//ImageJFunctions.show( filterY ).setTitle( "sum" );
		//SimpleMultiThreading.threadHaltUnClean();

		return tileToDynamicLambda;
	}

	protected static HashMap< Integer, List< Pair< Tile< ? >, Tile< TranslationModel2D > > > > fakePreAlign( final Collection< Tile< ? > > tiles, final SolveItem<?, ?, ?> solveItem )
	{
		LOG.info( "Pre-aligning with Translation to compute dynamic lambdas..." );
		
		final HashMap< Integer, List< Pair< Tile< ? >, Tile< TranslationModel2D > > > > zToTiles = new HashMap<>();

		final HashMap< Tile< ? >, Tile< TranslationModel2D > > tilesToFaketiles = new HashMap<>();
		final HashMap< Point, Tile< ? > > p1ToTile = new HashMap<>(); // to efficiently find a tile associated with a pointmatch

		for ( final Tile< ? > tile : tiles )
		{
			final Tile< TranslationModel2D > fakeTile = new Tile<>( new TranslationModel2D() );
			tilesToFaketiles.put( tile, fakeTile );

			for ( final PointMatch pm : tile.getMatches() )
				p1ToTile.put( pm.getP1(), tile );

			final Tile< ? > aTile = solveItem.groupedTileToTiles().get( tile ).get( 0 ); 
			final String tileId = solveItem.tileToIdMap().get( aTile );
			final int z = (int)Math.round( solveItem.idToTileSpec().get( tileId ).getZ() );
			zToTiles.computeIfAbsent(z, k -> new ArrayList<>())
					.add(new ValuePair<>(tile,fakeTile));
		}

		final HashSet< Tile<?> > alreadyVisited = new HashSet<>();

		for ( final Tile< ? > tile : tiles )
		{
			//LOG.info( "tile z " + Math.round( solveItem.idToTileSpec().get( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( tile ).get( 0 ) ) ).getZ() ) + " (" + tile.getMatches().size() + " matches). " );
			
			final HashMap< Tile< TranslationModel2D >, ArrayList< PointMatch > > matches = new HashMap<>();

			for ( final PointMatch pm : tile.getMatches() )
			{
				final Tile< ? > connectedTile = p1ToTile.get( pm.getP2() );
				
				if ( alreadyVisited.contains( connectedTile ) )
					continue;

				final Tile< TranslationModel2D > connectedFakeTile = tilesToFaketiles.get( connectedTile );

				final PointMatch newPM = new PointMatch(
						new Point( pm.getP1().getL().clone(), pm.getP1().getW().clone() ),
						new Point( pm.getP2().getL().clone(), pm.getP2().getW().clone() ),
						pm.getWeight() );
				
				matches.computeIfAbsent(connectedFakeTile, k -> new ArrayList<>())
						.add(newPM);
			}
		
			final Tile< TranslationModel2D > fakeTile = tilesToFaketiles.get( tile );

			for ( final Tile< TranslationModel2D > connectedFakeTile : matches.keySet() )
			{
				final ArrayList< PointMatch > newMatches = matches.get( connectedFakeTile ); 
				fakeTile.connect( connectedFakeTile, newMatches );
			}
			
			alreadyVisited.add( tile );
		}

		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles( tilesToFaketiles.values() );

		try
		{
			DoubleSummaryStatistics errors = computeErrors( tileConfig.getTiles() );
			LOG.info("errors: " + errors);

			final Map< Tile< ? >, Integer > tileToZ = new HashMap<>();

			for ( final Tile< ? > tile : tilesToFaketiles.keySet() )
			{
				final Tile< TranslationModel2D > fakeTile = tilesToFaketiles.get( tile );
				tileToZ.put( fakeTile, (int)Math.round( solveItem.idToTileSpec().get( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( tile ).get( 0 ) ) ).getZ() ) );
			}

			preAlignByLayerDistance( tileConfig, tileToZ );
			//tileConfig.preAlign();
			
			errors = computeErrors( tileConfig.getTiles() );
			LOG.info("errors: " + errors);
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			LOG.info("pre-align failed: ", e);
		}

		return zToTiles;
	}

	protected static double[] layerMinBounds( final List< Pair< Tile< ? >, Tile< TranslationModel2D > > > tilesList, final SolveItem< ?,?,? > solveItem )
	{
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;

		// a z-section can have more than one grouped tile if they are connected from above and below
		for ( final Pair< Tile< ? >, Tile< TranslationModel2D > > tiles : tilesList )
		{
			final Tile< ? > groupedTile = tiles.getA();
			final Tile< TranslationModel2D > fakeAlignedGroupedTile = tiles.getB();

			final AffineModel2D groupedModel = SolveTools.createAffine( fakeAlignedGroupedTile.getModel() );

			for ( final Tile< ? > tile : solveItem.groupedTileToTiles().get( groupedTile ) )
			{
				final String tileId = solveItem.tileToIdMap().get( tile );
				final TileSpec tileSpec = solveItem.idToTileSpec().get(tileId);

				final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();
				affine.preConcatenate( groupedModel );

				final double[] tmp = new double[2];

				tmp[ 0 ] = 0;
				tmp[ 1 ] = tileSpec.getHeight() / 2.0;

				affine.applyInPlace( tmp );

				minX = Math.min( minX, tmp[ 0 ] );
				minY = Math.min( minY, tmp[ 1 ] );

				tmp[ 0 ] = tileSpec.getWidth() / 2.0;
				tmp[ 1 ] = 0;

				affine.applyInPlace( tmp );

				minX = Math.min( minX, tmp[ 0 ] );
				minY = Math.min( minY, tmp[ 1 ] );
			}
		}

		return new double[] { minX, minY };
	}

	public static DoubleSummaryStatistics computeErrors(final Collection<? extends Tile<?>> tiles) {
		tiles.forEach(Tile::update);
		return tiles.stream().mapToDouble(t -> {t.update(); return t.getDistance();}).summaryStatistics();
	}

	public static List< Tile< ? > > preAlignByLayerDistance(
			final TileConfiguration tileConfig,
			final Map< Tile< ? >, Integer > tileToZ )
					throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		// first get order all tiles by
		// a) unaligned
		// b) aligned - which initially only contains the fixed ones
		final ArrayList<Tile<?>> unAlignedTiles = new ArrayList<>();
		final ArrayList<Tile<?>> alignedTiles = new ArrayList<>();

		final Tile< ? > firstTile;

		// if no tile is fixed, take another */
		if (tileConfig.getFixedTiles().isEmpty())
		{
			final Iterator< Tile< ? > > it = tileConfig.getTiles().iterator();
			alignedTiles.add( it.next() );

			firstTile = alignedTiles.get(0);
			
			while ( it.hasNext() )
				unAlignedTiles.add( it.next() );
		}
		else
		{
			for ( final Tile< ? > tile : tileConfig.getTiles() )
			{
				if ( tileConfig.getFixedTiles().contains( tile ) )
					alignedTiles.add( tile );
				else
					unAlignedTiles.add( tile );
			}
			firstTile = null;
		}

		// we go through each fixed/aligned tile and try to find a pre-alignment
		// for all other unaligned tiles
		for ( final ListIterator< Tile< ?> > referenceIterator = alignedTiles.listIterator(); referenceIterator.hasNext(); )
		{
			// once all tiles are aligned we can quit this loop
			if (unAlignedTiles.isEmpty())
				break;

			// get the next reference tile (either a fixed or an already aligned one
			final Tile< ? > referenceTile = referenceIterator.next();

			// transform all reference points into the reference coordinate system
			// so that we get the direct model even if we are not anymore at the
			// level of the fixed tile
			referenceTile.apply();

			//
			// NEW: we sort the unaligned by distance to the reference
			//
			unAlignedTiles.sort(new Comparator<Tile<?>>() {
				@Override
				public int compare(final Tile<?> o1, final Tile<?> o2) {
					return deltaZ(o2, referenceTile) - deltaZ(o1, referenceTile);
				}

				public int deltaZ(final Tile<?> tile1, final Tile<?> tile2) {
					return Math.abs(tileToZ.get(tile1) - tileToZ.get(tile2));
				}
			});
			
			// now we go through the unaligned tiles to see if we can align it to the current reference tile one
			for ( final ListIterator< Tile< ?> > targetIterator = unAlignedTiles.listIterator(); targetIterator.hasNext(); )
			{
				// get the tile that we want to preregister
				final Tile< ? > targetTile = targetIterator.next();

				// target tile is connected to reference tile
				if ( referenceTile.getConnectedTiles().contains( targetTile ) )
				{
					// extract all PointMatches between reference and target tile and fit a model only on these
					final ArrayList< PointMatch > pm = tileConfig.getConnectingPointMatches( targetTile, referenceTile );

					// are there enough matches?
					if ( pm.size() > targetTile.getModel().getMinNumMatches() )
					{
						// fit the model of the targetTile to the subset of matches
						// mapping its local coordinates target.p.l into the world
						// coordinates reference.p.w
						// this will give us an approximation for the global optimization
						targetTile.getModel().fit( pm );

						// now that we managed to fit the model we remove the
						// Tile from unaligned tiles and add it to aligned tiles
						targetIterator.remove();

						// now add the aligned target tile to the end of the reference list
						int countFwd = 0;

						while ( referenceIterator.hasNext() )
						{
							referenceIterator.next();
							++countFwd;
						}
						referenceIterator.add( targetTile );

						// move back to the current position
						// (+1 because it add just behind the current position)
						for ( int j = 0; j < countFwd + 1; ++j )
							referenceIterator.previous();
					}
				}

			}
		}

		if ( firstTile != null )
		{
			for ( final Tile< ? > templateTile : firstTile.getConnectedTiles() )
			{
				final ArrayList< PointMatch > pm = tileConfig.getConnectingPointMatches( firstTile, templateTile );
			
				if ( pm.size() > firstTile.getModel().getMinNumMatches() )
				{
					firstTile.getModel().fit( pm );
					break;
				}
			}
		}
	
		return unAlignedTiles;
	}

	public static AffineModel2D createAffine( final Affine2D< ? > model )
	{
		final AffineModel2D m = new AffineModel2D();
		m.set( model.createAffine() );

		return m;
	}

	public static List<PointMatch> duplicate(final List<PointMatch> pms)
	{
		final List< PointMatch > copy = new ArrayList<>();

		for ( final PointMatch pm : pms )
			copy.add( new PointMatch( pm.getP1().clone(), pm.getP2().clone(), pm.getWeight() ) );

		return copy;
	}

	public static List<PointMatch> createRelativePointMatches(
			final List<PointMatch> absolutePMs,
			final CoordinateTransform pTransform,
			final CoordinateTransform qTransform) {

		final List<PointMatch> relativePMs = new ArrayList<>(absolutePMs.size());
		if (absolutePMs.isEmpty())
			return relativePMs;

		final PointMatch firstMatch = absolutePMs.get(0);
		final int n = firstMatch.getP1().getL().length;

		for (final PointMatch absPM : absolutePMs) {
			final double[] pLocal = fastClone(absPM.getP1().getL(), n);
			final double[] qLocal = fastClone(absPM.getP2().getL(), n);

			if (pTransform != null)
				pTransform.applyInPlace(pLocal);

			if (qTransform != null)
				qTransform.applyInPlace(qLocal);

			relativePMs.add(new PointMatch(new Point(pLocal), new Point(qLocal), absPM.getWeight()));
		}

		return relativePMs;
	}

	// JMH micro-benchmarking suggests that this is the fastest way to clone an array:
	// the benchmark is indecisive whether replacing the loop with System.arraycopy() is beneficial, but both
	// are faster than Arrays.copyOf() and double[]::clone
	@SuppressWarnings("ManualArrayCopy")
	private static double[] fastClone(final double[] in, final int length) {
		final double[] out = new double[length];
		for (int i = 0; i < length; i++) {
			out[i] = in[i];
		}
		return out;
	}


	public static AffineModel2D createAffineModel( final RigidModel2D rigid )
	{
		final double[] array = new double[ 6 ];
		rigid.toArray( array );
		final AffineModel2D affine = new AffineModel2D();
		affine.set( array[ 0 ], array[ 1 ], array[ 2 ], array[ 3 ], array[ 4 ], array[ 5 ] );
		return affine;
	}

	//protected abstract void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException;

	// must be called after all Tilespecs are updated
	public static void completeStack( final String targetStack, final RunParameters runParams ) throws IOException
	{
		runParams.targetDataClient.setStackState( targetStack, StackState.COMPLETE );
	}

	public static < B extends Model< B > & Affine2D< B > > Pair< Tile< B >, AffineModel2D > buildTileFromSpec(
			final B instance,
			final int samplesPerDimension,
			final TileSpec tileSpec )
	{
        final AffineModel2D lastTransform = loadLastTransformFromSpec( tileSpec ).copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        try {
            ScriptUtil.fit(instance, lastTransform, sampleWidth, sampleHeight, samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(instance.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new ValuePair<>(new Tile<>(instance), lastTransform);
	}


	public static < B extends Model< B > & Affine2D< B > > Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > buildTileFromSpec(
			final int samplesPerDimension,
			final ModelType regularizerModelType,
			final double startLambda,
			final TileSpec tileSpec )
	{
        final AffineModel2D lastTransform = loadLastTransformFromSpec( tileSpec );
        final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        final B regularizer = regularizerModelType.getInstance();

        try {
            ScriptUtil.fit(regularizer, lastTransformCopy, sampleWidth, sampleHeight, samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(regularizer.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new ValuePair<>(
        		new Tile<>(new InterpolatedAffineModel2D<>(
        				lastTransformCopy,
        				regularizer,
        				startLambda)), // note: lambda gets reset during optimization loops
        		lastTransform.copy() );
	}

	public static < M extends Model< M > & Affine2D< M > > Tile< M > buildTile(
			final AffineModel2D lastTransform,
			final M model,
			final int width,
			final int height,
			final int samplesPerDimension
			)
	{
        final double sampleWidth = (width - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (height - 1.0) / (samplesPerDimension - 1.0);

        try
        {
            ScriptUtil.fit(model, lastTransform, sampleWidth, sampleHeight, samplesPerDimension);
        }
        catch (final Throwable t)
        {
            throw new IllegalArgumentException(model.getClass() + " model derivation failed, cause: " + t.getMessage(), t);
        }

        return new Tile<>(model);
	}

	public static TileSpec getTileSpec(
			final String stack,
			final RunParameters runParams,
			final String sectionId,
			final String tileId ) throws IOException {
		
		return getTileSpec( runParams.sectionIdToZMap, runParams.zToTileSpecsMap, runParams.renderDataClient, stack, sectionId, tileId );
	}

	public static TileSpec getTileSpec(
			final Map<String, ? extends List<Double>> sectionIdToZMap,
			final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap,
			final RenderDataClient renderDataClient,
			final String stack,
			final String sectionId,
			final String tileId ) throws IOException {

        TileSpec tileSpec = null;

        if (sectionIdToZMap.containsKey(sectionId)) {

            for (final Double z : sectionIdToZMap.get(sectionId)) {

                if ( !zToTileSpecsMap.containsKey(z)) {

//                    if (runParams.totalTileCount > 100000) {
//                        throw new IllegalArgumentException("More than 100000 tiles need to be loaded - please reduce z values");
//                    }

                    final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

                    // check for accidental use of rough aligned stack ...
                    resolvedTiles.getTileSpecs().forEach(ts -> {
                        if (ts.getLastTransform() instanceof ReferenceTransformSpec) {
                            throw new IllegalStateException(
                                    "last transform for tile " + ts.getTileId() +
                                    " is a reference transform which will break this fragile client, " +
                                    "make sure --stack is not a rough aligned stack ");
                        }
                    });

                    resolvedTiles.resolveTileSpecs();
                    zToTileSpecsMap.put(z, resolvedTiles);
                    //runParams.totalTileCount += resolvedTiles.getTileCount();
                }

                final ResolvedTileSpecCollection resolvedTileSpecCollection = zToTileSpecsMap.get(z);
                tileSpec = resolvedTileSpecCollection.getTileSpec(tileId);

                if (tileSpec != null) {
                    break;
                }
            }
            
        }

        return tileSpec;
    }

	public static RenderParameters getRenderParametersForTile( final String owner,
			final String project, final String stack, final String tileId,
			final double renderScale )
	{
		final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/" + owner + "/project/" + project + "/stack/" + stack + "/tile/";
		final String urlSuffix = "/render-parameters?scale=" + renderScale;
		// TODO: add &fillWithNoise=true ?
		// TODO: add &excludeMask=true ?
		final String url = baseTileUrl + tileId + urlSuffix;

		final RenderParameters renderParameters = RenderParameters.loadFromUrl( url );
		renderParameters.setDoFilter( false );
		renderParameters.initializeDerivedValues();

		renderParameters.validate();

		// remove mipmapPathBuilder so that we don't get exceptions when /nrs is
		// not mounted
		renderParameters.setMipmapPathBuilder( null );
		renderParameters.applyMipmapPathBuilderToTileSpecs();

		return renderParameters;
	}
	//
	// overwrites the area that was re-aligned or it preconcatenates
	//
	public static void saveTargetStackTiles(
			final String stack, // parameters.stack
			final String targetStack, // parameters.targetStack
			final RunParameters runParams,
			final Map< String, AffineModel2D > idToModel,
			final AffineModel2D relativeModel,
			final List< Double > zToSave,
			final TransformApplicationMethod applyMethod ) throws IOException
	{
		LOG.info( "saveTargetStackTiles: entry, saving tile specs in {} layers", zToSave.size() );

		for ( final Double z : zToSave )
		{
			final ResolvedTileSpecCollection resolvedTiles;

			if ( !runParams.zToTileSpecsMap.containsKey( z ) )
			{
				resolvedTiles = runParams.renderDataClient.getResolvedTiles( stack, z );
			}
			else
			{
				resolvedTiles = runParams.zToTileSpecsMap.get( z );
			}

			if ( idToModel != null || relativeModel != null )
			{
				for (final TileSpec tileSpec : resolvedTiles.getTileSpecs())
				{
					final String tileId = tileSpec.getTileId();
					final AffineModel2D model;
	
					if ( applyMethod.equals(  TransformApplicationMethod.REPLACE_LAST  ) )
						model = idToModel.get( tileId );
					else if ( applyMethod.equals( TransformApplicationMethod.PRE_CONCATENATE_LAST ))
						model = relativeModel;
					else
						throw new RuntimeException( "not supported: " + applyMethod );
	
					if ( model != null )
					{
						resolvedTiles.addTransformSpecToTile( tileId,
								getTransformSpec( model ),
								applyMethod );
					}
				}
			}

			if ( resolvedTiles.getTileCount() > 0 )
				runParams.targetDataClient.saveResolvedTiles( resolvedTiles, targetStack, null );
			else
				LOG.info( "skipping tile spec save since no specs are left to save" );
		}

		LOG.info( "saveTargetStackTiles: exit" );
	}

	/**
	 * Note: this method relies on the internal structure of Affine2D
	 * as such, it is fragile and may break if the internal structure of Affine2D changes.
	 * <p>
	 * Current layout:
	 * data[0] = m00;
	 * data[1] = m10;
	 * data[2] = m01;
	 * data[3] = m11;
	 * data[4] = m02;
	 * data[5] = m12;
	 */
	public static LeafTransformSpec getTransformSpec(final Affine2D<?> forModel) {
		final double[] m = new double[6];
		forModel.toArray(m);

		final String data = String.valueOf(m[0]) + ' ' + m[1] + ' ' + m[2] + ' ' + m[3] + ' ' + m[4] + ' ' + m[5];
		return new LeafTransformSpec(mpicbg.trakem2.transform.AffineModel2D.class.getName(), data);
	}

	/**
	 * Note: this method relies on the internal structure of AffineTransform2D
	 * as such, it is fragile and may break if the internal structure of AffineTransform2D changes, see, e.g.,
	 * <a href="https://github.com/imglib/imglib2-realtransform/commit/28986382280012a338cfed879956fbf6ac1f0f2e">this commit</a>
	 * <p>
	 * OLD:               NEW:
	 * data[0] = a.m00;   data[0] = a.m00;
	 * data[1] = a.m01;   data[1] = a.m01;
	 * data[3] = a.m02;   data[2] = a.m02;
	 * data[4] = a.m10;   data[3] = a.m10;
	 * data[6] = a.m11;   data[4] = a.m11;
	 * data[7] = a.m12;   data[5] = a.m12;
	 */
	public static LeafTransformSpec getTransformSpec( final AffineTransform2D forModel )
	{
		final double[] m = new double[ 6 ];
		forModel.toArray( m );

		final String data = String.valueOf( m[ 0 ] ) + ' ' + m[ 3 ] + ' ' + m[ 1 ] + ' ' + m[ 4 ] + ' ' + m[ 2 ] + ' ' + m[ 5 ];
		System.out.println( data );
		return new LeafTransformSpec( mpicbg.trakem2.transform.AffineModel2D.class.getName(), data );
	}

	public static AffineModel2D loadLastTransformFromSpec( final TileSpec tileSpec )
	{
        final CoordinateTransformList<CoordinateTransform> postMatchTransformList =
				tileSpec.getPostMatchingTransformList();
		final List<CoordinateTransform> simpleList = postMatchTransformList.getList( null );

		// Assuming that there one and only one post match Affine transform.
		// Throw an exception if this assumption is incorrect to force us to verify new use cases.
		if (simpleList.size() != 1)	{
			throw new RuntimeException("tile " + tileSpec.getTileId() + " has " + simpleList.size() +
									   " post match transforms but was expecting one and only one");
		}

		final CoordinateTransform lastTransform = simpleList.get(0);
		if (! (lastTransform instanceof AffineModel2D)) {
			throw new RuntimeException("tile " + tileSpec.getTileId() + " post match transform is a " +
									   lastTransform.getClass().getName() + " but must be an AffineModel2D");
		}

        return (AffineModel2D) lastTransform;
	}

	private static final Logger LOG = LoggerFactory.getLogger(SolveTools.class);
}
