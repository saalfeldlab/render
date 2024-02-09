package org.janelia.render.client.solver.visualize.imglib2;

import static net.imglib2.img.basictypeaccess.AccessFlags.DIRTY;
import static net.imglib2.img.basictypeaccess.AccessFlags.VOLATILE;

import java.io.IOException;
import java.util.Random;
import java.util.Set;

import org.janelia.render.client.solver.visualize.lazy.Lazy;

import bdv.img.cache.CreateInvalidVolatileCell;
import bdv.img.cache.VolatileCachedCellImg;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileRandomAccessibleIntervalView;
import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.util.volatiles.VolatileViewData;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.WeakRefVolatileCache;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.CreateInvalid;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.cache.volatiles.VolatileCache;
import net.imglib2.img.WrappedImg;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.DataAccess;
import net.imglib2.img.basictypeaccess.volatiles.VolatileArrayDataAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;

public class VolatileTmp {

	/*
	 * Alternative implementation that exposes the cache is written by tobias,
	 * VolatileViewData will contain the cache, should be in imglib2-cache-1.0.0-beta-17
	 */
	@Deprecated
	public static < T, V extends Volatile< T > > Pair< RandomAccessibleInterval< V >, VolatileCache > wrapAsVolatile(
			final RandomAccessibleInterval< T > rai,
			final SharedQueue queue,
			final CacheHints hints )
	{
		@SuppressWarnings( "unchecked" )
		Pair< VolatileViewData< T, V >, VolatileCache > pair = ( Pair ) wrapAsVolatileViewData( rai, queue, hints );
		final VolatileViewData< T, V > viewData = pair.getA();
		return new ValuePair( new VolatileRandomAccessibleIntervalView<>( viewData ), pair.getB() );
	}

	@SuppressWarnings( "unchecked" )
	private static < T, V extends Volatile< T > > Pair< VolatileViewData< T, V >, VolatileCache > wrapAsVolatileViewData(
			final RandomAccessible< T > rai,
			final SharedQueue queue,
			final CacheHints hints )
	{
		if ( rai instanceof CachedCellImg )
		{
			@SuppressWarnings( "rawtypes" )
			final Object o = wrapCachedCellImg( ( CachedCellImg ) rai, queue, hints );
			/*
			 * Need to assign to a Object first to satisfy Eclipse... Otherwise
			 * the following "unnecessary cast" will be removed, followed by
			 * compile error. Proposed solution: Add cast. Doh...
			 */
			final VolatileViewData< T, V > viewData = ( VolatileViewData< T, V > ) ((Pair)o).getA();
			return new ValuePair( viewData, ((Pair)o).getB());
		}
		else if ( rai instanceof IntervalView )
		{
			final IntervalView< T > view = ( IntervalView< T > ) rai;
			Pair< VolatileViewData< T, V >, VolatileCache > pair = wrapAsVolatileViewData( view.getSource(), queue, hints );
			final VolatileViewData< T, V > sourceData = pair.getA();
			return new ValuePair( new VolatileViewData<>(
					new IntervalView<>( sourceData.getImg(), view ),
					sourceData.getCacheControl(),
					sourceData.getType(),
					sourceData.getVolatileType() ), pair.getB() );
		}
		else if ( rai instanceof MixedTransformView )
		{
			final MixedTransformView< T > view = ( MixedTransformView< T > ) rai;
			Pair< VolatileViewData< T, V >, VolatileCache > pair = wrapAsVolatileViewData( view.getSource(), queue, hints );
			final VolatileViewData< T, V > sourceData = pair.getA();
			return new ValuePair( new VolatileViewData<>(
					new MixedTransformView<>( sourceData.getImg(), view.getTransformToSource() ),
					sourceData.getCacheControl(),
					sourceData.getType(),
					sourceData.getVolatileType() ), pair.getB() );
		}
		else if ( rai instanceof WrappedImg )
		{
			return wrapAsVolatileViewData( ( ( WrappedImg< T > ) rai ).getImg(), queue, hints );
		}

		throw new IllegalArgumentException();
	}

	@SuppressWarnings( "unchecked" )
	private static < T extends NativeType< T >, V extends Volatile< T > & NativeType< V >, A extends DataAccess> Pair< VolatileViewData< T, V >, VolatileCache< Long, Cell< A > > > wrapCachedCellImg(
			final CachedCellImg< T, A > cachedCellImg,
			SharedQueue queue,
			CacheHints hints )
	{
		final T type = cachedCellImg.createLinkedType();
		final CellGrid grid = cachedCellImg.getCellGrid();
		final Cache< Long, Cell< A > > cache = cachedCellImg.getCache();

		final Set< AccessFlags > flags = AccessFlags.ofAccess( cachedCellImg.getAccessType() );
		if ( !flags.contains( VOLATILE ) )
			throw new IllegalArgumentException( "underlying " + CachedCellImg.class.getSimpleName() + " must have volatile access type" );
		final boolean dirty = flags.contains( DIRTY );

		final V vtype = ( V ) VolatileTypeMatcher.getVolatileTypeForType( type );
		if ( queue == null )
			queue = new SharedQueue( 1, 1 );
		if ( hints == null )
			hints = new CacheHints( LoadingStrategy.VOLATILE, 0, false );

		@SuppressWarnings( "rawtypes" )
		final Pair< VolatileCachedCellImg< T, A >, VolatileCache< Long, Cell< A > > > pair = createVolatileCachedCellImg( grid, vtype, dirty, ( Cache ) cache, queue, hints );
		final VolatileCachedCellImg< V, ? > img = (VolatileCachedCellImg< V, ? >)(Object)pair.getA();

		return new ValuePair<>( new VolatileViewData<>( img, queue, type, vtype ), pair.getB() );
	}

	private static < T extends NativeType< T >, A extends VolatileArrayDataAccess< A > > Pair< VolatileCachedCellImg< T, A >, VolatileCache< Long, Cell< A > > > createVolatileCachedCellImg(
			final CellGrid grid,
			final T type,
			final boolean dirty,
			final Cache< Long, Cell< A > > cache,
			final SharedQueue queue,
			final CacheHints hints )
	{
		final CreateInvalid< Long, Cell< A > > createInvalid = CreateInvalidVolatileCell.get( grid, type, dirty );
		final VolatileCache< Long, Cell< A > > volatileCache = new WeakRefVolatileCache<>( cache, queue, createInvalid );
		final VolatileCachedCellImg< T, A > volatileImg = new VolatileCachedCellImg<>( grid, type, hints, volatileCache.unchecked()::get );
		return new ValuePair<VolatileCachedCellImg< T, A >, VolatileCache< Long, Cell< A > >>( volatileImg, volatileCache );
		//return volatileImg;
	}

	public static void simpleCacheTest()
	{
		Interval interval = new FinalInterval( 512, 512, 256 );

		final Random rnd = new Random( 35 );
		CachedCellImg<FloatType, ?> cachedCellImg =
				Lazy.process(
					interval,
					new int[] { 64, 64, 32 },
					new FloatType(),
					AccessFlags.setOf( AccessFlags.VOLATILE ),
					out -> {
						final float base = rnd.nextFloat() * 32768 + 32768;
						Views.iterable( out ).forEach( p -> p.set( rnd.nextFloat() * base ));
						});

		final RandomAccessibleInterval<FloatType> cachedImg =
				Views.translate(
						cachedCellImg,
						new long[] { 10, 10, 10 } );

		final SharedQueue queue = new SharedQueue( 8, 1 );
		final Pair< RandomAccessibleInterval< VolatileFloatType >, VolatileCache > pair = VolatileTmp.wrapAsVolatile( cachedImg, queue, null );

		Bdv source = BdvFunctions.show( pair.getA(), "gg" );

		while ( source != null )
		{
			SimpleMultiThreading.threadWait( 2000 );
			System.out.println( "repainting " );

			//cachedCellImg.getCache().invalidateAll();
			pair.getB().invalidateAll();
			source.getBdvHandle().getViewerPanel().requestRepaint();
		}
	}

	public static void main( String[] args ) throws IOException
	{
		simpleCacheTest();
	}
}
