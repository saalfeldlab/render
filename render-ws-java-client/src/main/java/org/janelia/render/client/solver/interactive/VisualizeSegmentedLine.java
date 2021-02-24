package org.janelia.render.client.solver.interactive;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.TransformListener;

public class VisualizeSegmentedLine implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	protected static final double SPLINE_STEP = 0.01;

	protected final List< double[] > points;
	protected final List< double[] > transformedPoints = new ArrayList<>();
	protected final AffineTransform3D viewerTransform;
	protected final Bdv bdv;

	protected int HANDLE_RADIUS = 5;
	protected int HANDLE_RADIUS_SELECTED = 10;

	protected double SNAP_Z = 0.1;

	protected Color backColor = new Color( 0x00994499 );
	protected Color hitColor = Color.RED;
	protected Color frontColor = Color.GREEN;
	protected Stroke normalStroke = new BasicStroke();

	public VisualizeSegmentedLine( final Bdv bdv, final List< double[] > points )
	{
		this.bdv = bdv;
		this.points = points;
		this.viewerTransform = new AffineTransform3D();
	}

	public VisualizeSegmentedLine( final Bdv bdv, final List< double[] > points, final Color frontColor, final Color backColor, final Color hitColor )
	{
		this.bdv = bdv;
		this.points = points;
		this.viewerTransform = new AffineTransform3D();

		this.frontColor = frontColor;
		this.backColor = backColor;
		this.hitColor = hitColor;
	}

	@Override
	public void transformChanged( final AffineTransform3D t )
	{
		synchronized ( viewerTransform )
		{
			viewerTransform.set( t );
		}
	}

	public void install()
	{
		bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer( this );
		bdv.getBdvHandle().getViewerPanel().addRenderTransformListener( this );
	}

	public void uninstall()
	{
		bdv.getBdvHandle().getViewerPanel().getDisplay().removeOverlayRenderer( this );
		bdv.getBdvHandle().getViewerPanel().removeTransformListener( this );
	}

	@Override
	public void drawOverlays(Graphics g)
	{
		final Graphics2D graphics = ( Graphics2D ) g;

		transformedPoints.clear();

		for ( final double[] point : points )
		{
			final double[] tmp = new double[ 3 ];
			viewerTransform.apply( point, tmp );
			transformedPoints.add( tmp );
		}

		graphics.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );

		// for spline drawing
		final MonotoneCubicSpline spline =
				MonotoneCubicSpline.createMonotoneCubicSpline(
						points.stream().map( p -> new RealPoint( p ) ).collect( Collectors.toList() ) );

		final RealPoint p0 = new RealPoint( points.get( 0 ).length );
		final RealPoint p1 = new RealPoint( points.get( 0 ).length );

		final double[] d0 = new double[ p0.numDimensions() ];
		final double[] d1 = new double[ p0.numDimensions() ];

		// draw lines 
		if ( transformedPoints.size() > 1 )
		{
			final GeneralPath front = new GeneralPath();
			final GeneralPath back = new GeneralPath();

			// draw lines
			for ( int i = 1; i < transformedPoints.size(); ++i )
				splitLine( transformedPoints.get( i - 1 ), transformedPoints.get( i ), front, back );

			// draw spline
			spline.interpolate( 0, p0 );

			for ( double x = SPLINE_STEP; x < points.size() - 1; x += SPLINE_STEP )
			{
				spline.interpolate( x, p1 );

				p0.localize( d0 );
				p1.localize( d1 );

				viewerTransform.apply( d0, d0 );
				viewerTransform.apply( d1, d1 );

				splitLine( d0, d1, front, back );

				p0.setPosition( p1 );
			}

			graphics.setStroke( normalStroke );
			graphics.setPaint( backColor );
			graphics.draw( back );

			graphics.setStroke( normalStroke );
			graphics.setPaint( frontColor );
			graphics.draw( front );
		}

		drawPoints( graphics );
	}

	protected void drawPoints( final Graphics2D graphics )
	{
		for ( int i = 0; i < transformedPoints.size(); ++i )
			drawPoint( graphics, transformedPoints.get( i ), false );		
	}

	protected void drawPoint( final Graphics2D graphics, final double[] p, final boolean isHighlighted )
	{
		final int r = isHighlighted ? HANDLE_RADIUS_SELECTED : HANDLE_RADIUS;

		final Ellipse2D cornerHandle = new Ellipse2D.Double(
				p[ 0 ] - r, p[ 1 ] - r, 2 * r, 2 * r );

		final Color cornerColor;

		if ( hitColor != null && Math.abs( p[ 2 ] ) < SNAP_Z )
			cornerColor = hitColor;
		else if ( p[ 2 ] > 0 )
			cornerColor = backColor;
		else
			cornerColor = frontColor;

		graphics.setColor( cornerColor );
		graphics.fill( cornerHandle );
		graphics.setColor( cornerColor.darker().darker() );
		graphics.draw( cornerHandle );
	}

	protected static void splitLine( final double[] a, final double[] b, final GeneralPath before, final GeneralPath behind )
	{
		final double[] pa = new double[] { a[ 0 ], a[ 1 ] };
		final double[] pb = new double[] { b[ 0 ], b[ 1 ] };

		if ( a[ 2 ] <= 0 )
		{
			before.moveTo( pa[ 0 ], pa[ 1 ] );
			if ( b[ 2 ] <= 0 )
				before.lineTo( pb[ 0 ], pb[ 1 ] );
			else
			{
				final double[] t = new double[ 3 ];
				final double d = a[ 2 ] / ( a[ 2 ] - b[ 2 ] );
				t[ 0 ] = ( b[ 0 ] - a[ 0 ] ) * d + a[ 0 ];
				t[ 1 ] = ( b[ 1 ] - a[ 1 ] ) * d + a[ 1 ];

				before.lineTo( t[ 0 ], t[ 1 ] );
				behind.moveTo( t[ 0 ], t[ 1 ] );
				behind.lineTo( pb[ 0 ], pb[ 1 ] );
			}
		}
		else
		{
			behind.moveTo( pa[ 0 ], pa[ 1 ] );
			if ( b[ 2 ] > 0 )
				behind.lineTo( pb[ 0 ], pb[ 1 ] );
			else
			{
				final double[] t = new double[ 3 ];
				final double d = a[ 2 ] / ( a[ 2 ] - b[ 2 ] );
				t[ 0 ] = ( b[ 0 ] - a[ 0 ] ) * d + a[ 0 ];
				t[ 1 ] = ( b[ 1 ] - a[ 1 ] ) * d + a[ 1 ];
				behind.lineTo( t[ 0 ], t[ 1 ] );
				before.moveTo( t[ 0 ], t[ 1 ] );
				before.lineTo( pb[ 0 ], pb[ 1 ] );
			}
		}
	}

	@Override
	public void setCanvasSize(int width, int height) {}

	public static void main( final String[] args )
	{
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		final Random random = new Random();

		final Img< UnsignedByteType > img = ArrayImgs.unsignedBytes( 100, 100, 50 );
		img.forEach( t -> t.set( random.nextInt( 128 ) ) );

		final AffineTransform3D imageTransform = new AffineTransform3D();
		imageTransform.set( 2, 2, 2 ); //anisotropic in z
		final Bdv bdv = BdvFunctions.show( img, "image", BdvOptions.options().sourceTransform( imageTransform ) );

		final ArrayList< double[] > points = new ArrayList<>();

		points.add( new double[] { 10, 10 ,10 } );
		points.add( new double[] { 50, 20 ,30 } );
		points.add( new double[] { 90, 90 ,90 } );

		VisualizeSegmentedLine line = new VisualizeSegmentedLine( bdv, points, Color.yellow, Color.yellow.darker(), null );
		line.install();

		/*
		final Interval initialInterval = Intervals.createMinMax( 30, 30, 15, 80, 80, 40 );
		final Interval rangeInterval = Intervals.createMinMax( 0, 0, 0, 100, 100, 50 );
		final TransformedBoxSelectionDialog.Result result = BdvFunctions.selectBox(
				bdv,
				imageTransform,
				initialInterval,
				rangeInterval,
				BoxSelectionOptions.options()
						.title( "Select box to fill" )
						.selectTimepointRange()
						.initialTimepointRange( 0, 5 ) );

		if ( result.isValid() )
		{
			Views.interval( Views.extendZero( img ), result.getInterval() ).forEach( t -> t.set( 255 ) );
			bdv.getBdvHandle().getViewerPanel().requestRepaint();
		}
		*/
	}
}