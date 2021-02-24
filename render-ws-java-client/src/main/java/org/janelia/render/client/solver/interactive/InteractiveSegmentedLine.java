package org.janelia.render.client.solver.interactive;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.InputTrigger;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.Behaviours;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.AbstractTransformAnimator;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.TransformListener;
import net.imglib2.util.Util;

public class InteractiveSegmentedLine implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	private static final double SPLINE_STEP = 0.01;
	private static final String SEGMENTED_LINE_MAP = "segmented-line";
	private static final String BLOCKING_MAP = "segmented-line-blocking";
	private static final String SEGMENTED_LINE_TOGGLE_EDITOR = "edit segmented-line";
	private static final String[] SEGMENTED_LINE_TOGGLE_EDITOR_KEYS = new String[] { "button1" };//, "ctrl K" };

	private final List< double[] > points = new ArrayList<>();
	private final List< double[] > transformedPoints = new ArrayList<>();
	private int pointId = -1;
	private int canvasWidth;
	private int canvasHeight;
	private final AffineTransform3D viewerTransform;
	private PointHighlighter pointHighLighter;
	private final Bdv bdv;

	private int HANDLE_RADIUS = 5;
	private int HANDLE_RADIUS_SELECTED = 10;

	private double SNAP_Z = 0.1;

	private final TriggerBehaviourBindings triggerbindings;
	private final Behaviours behaviours;
	private final BehaviourMap blockMap;

	private final Color backColor = new Color( 0x00994499 );
	private final Color hitColor = Color.RED;
	private final Color frontColor = Color.GREEN;
	private final Stroke normalStroke = new BasicStroke();

	public InteractiveSegmentedLine( final Bdv bdv )
	{
		this.bdv = bdv;
		this.triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		this.viewerTransform = new AffineTransform3D();
		this.pointHighLighter = new PointHighlighter( 10 );

		/*
		 * Create DragPointBehaviour
		 */
		behaviours = new Behaviours( new InputTriggerConfig(), "bdv" );
		behaviours.behaviour( new DragPointBehaviour(), SEGMENTED_LINE_TOGGLE_EDITOR, SEGMENTED_LINE_TOGGLE_EDITOR_KEYS );

		/*
		 * setting up keystrokes
		 */
		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[]{new InputTriggerDescription(
								new String[]{"not mapped"},
								"drag rotate slow",
								"bdv")}));

		ActionMap ksActionMap = new ActionMap();
		InputMap ksInputMap = new InputMap();
		KeyStrokeAdder ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new AddPoint().register(ksActionMap, ksKeyStrokeAdder);
		new DeletePoint().register(ksActionMap, ksKeyStrokeAdder);
		new JumpToPoint().register(ksActionMap, ksKeyStrokeAdder);

		bdv.getBdvHandle().getKeybindings().addActionMap("persistence", ksActionMap);
		bdv.getBdvHandle().getKeybindings().addInputMap("persistence", ksInputMap);

		/*
		 * Create BehaviourMap to block behaviours interfering with
		 * DragPointBehaviour. The block map is only active while a corner
		 * is highlighted.
		 */
		blockMap = new BehaviourMap();
	}

	@Override
	public void transformChanged( final AffineTransform3D t )
	{
		synchronized ( viewerTransform )
		{
			viewerTransform.set( t );
		}
	}

	public void test()
	{
		points.add( new double[] { 10, 10 ,10 } );
		points.add( new double[] { 50, 20 ,30 } );
		points.add( new double[] { 90, 90 ,90 } );
		install();
	}

	public void install()
	{
		bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer( this );
		bdv.getBdvHandle().getViewerPanel().addRenderTransformListener( this );
		bdv.getBdvHandle().getViewerPanel().getDisplay().addHandler( pointHighLighter );

		//refreshBlockMap();
		//updateEditability();
		buildBlockMap();
		behaviours.install( triggerbindings, SEGMENTED_LINE_MAP );
	}

	public void uninstall()
	{
		bdv.getBdvHandle().getViewerPanel().getDisplay().removeOverlayRenderer( this );
		bdv.getBdvHandle().getViewerPanel().removeTransformListener( this );
		bdv.getBdvHandle().getViewerPanel().getDisplay().removeHandler( pointHighLighter );

		triggerbindings.removeInputTriggerMap( SEGMENTED_LINE_MAP );
		triggerbindings.removeBehaviourMap( SEGMENTED_LINE_MAP );

		unblock();
	}

	private void block()
	{
		triggerbindings.addBehaviourMap( BLOCKING_MAP, blockMap );
	}

	private void unblock()
	{
		triggerbindings.removeBehaviourMap( BLOCKING_MAP );
	}

	private void buildBlockMap()
	{
		triggerbindings.removeBehaviourMap( BLOCKING_MAP );

		final Set< InputTrigger > segmentedLineTriggers = new HashSet<>();
		for ( final String s : SEGMENTED_LINE_TOGGLE_EDITOR_KEYS )
			segmentedLineTriggers.add( InputTrigger.getFromString( s ) );

		final Map< InputTrigger, Set< String > > bindings = triggerbindings.getConcatenatedInputTriggerMap().getAllBindings();
		final Set< String > behavioursToBlock = new HashSet<>();
		for ( final InputTrigger t : segmentedLineTriggers )
			behavioursToBlock.addAll( bindings.getOrDefault( t, Collections.emptySet() ) );

		blockMap.clear();
		final Behaviour block = new Behaviour() {};
		for ( final String key : behavioursToBlock )
			blockMap.put( key, block );
	}

	@Override
	public void drawOverlays(Graphics g)
	{
		final Graphics2D graphics = ( Graphics2D ) g;

		final AffineTransform3D transform = viewerTransform.copy();

		transformedPoints.clear();

		for ( final double[] point : points )
		{
			final double[] tmp = new double[ 3 ];
			transform.apply( point, tmp );
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

				transform.apply( d0, d0 );
				transform.apply( d1, d1 );

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

		for ( int i = 0; i < transformedPoints.size(); ++i )
			drawPoint( graphics, transformedPoints.get( i ), i==pointId );
	}

	private void drawPoint( final Graphics2D graphics, final double[] p, final boolean isHighlighted )
	{
		final int r = isHighlighted ? HANDLE_RADIUS_SELECTED : HANDLE_RADIUS;

		final Ellipse2D cornerHandle = new Ellipse2D.Double(
				p[ 0 ] - r, p[ 1 ] - r, 2 * r, 2 * r );

		final Color cornerColor;

		if ( Math.abs( p[ 2 ] ) < SNAP_Z )
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

	private static void splitLine( final double[] a, final double[] b, final GeneralPath before, final GeneralPath behind )
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
	public void setCanvasSize(int width, int height)
	{
		this.canvasWidth = width;
		this.canvasHeight = height;
	}

	private void setHighlightedPoint( final int id )
	{
		final int oldId = pointId;
		pointId = ( id >= 0 && id < points.size() ) ? id : -1;
		if ( pointId != oldId )
		{
			if ( pointId < 0 )
				unblock();
			else
				block();
		}
	}

	public class PointHighlighter extends MouseMotionAdapter
	{
		private final double squTolerance;

		PointHighlighter( final double tolerance )
		{
			squTolerance = tolerance * tolerance;
		}

		@Override
		public void mouseMoved( final MouseEvent e )
		{
			final int x = e.getX();
			final int y = e.getY();

			for ( int i = 0; i < points.size(); i++ )
			{
				final double[] point = transformedPoints.get( i );
				final double dx = x - point[ 0 ];
				final double dy = y - point[ 1 ];
				final double dr2 = dx * dx + dy * dy;
				if ( dr2 < squTolerance )
				{
					setHighlightedPoint( i );
					return;
				}
			}
			setHighlightedPoint( -1 );
		}
	}

	public class DragPointBehaviour implements DragBehaviour
	{
		private ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();
		boolean moving = false;
		int movintPointId = -1;

		@Override
		public void init(int x, int y)
		{
			if ( pointId >= 0 )
			{
				moving = true;
				movintPointId = pointId;
			}
		}

		@Override
		public void drag(int x, int y)
		{
			if ( moving )
			{
				final AffineTransform3D viewerTransform = viewer.state().getViewerTransform();

				// map original location to screen
				double[] p = points.get( movintPointId );
				viewerTransform.apply( p, p );

				// update x,y and not z
				final double[] tmp = new double[] { x, y, p[ 2 ] };

				// map back to global coordinates and store
				viewerTransform.applyInverse(tmp, tmp);
				points.set( movintPointId, tmp );

				viewer.requestRepaint();
			}
		}

		@Override
		public void end(int x, int y)
		{
			moving = false;
			movintPointId = -1;
		}
	}

	public class AddPoint extends AbstractNamedAction
	{
		private static final long serialVersionUID = 3640052275162419689L;

		public AddPoint() { super( "Add point" ); }

		private ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();

		@Override
		public void actionPerformed(ActionEvent e)
		{
			// a point is highlighted
			if ( pointId >= 0)
			{
				System.out.println( "cannot add new point as one is currently highlighted." );
			}
			else
			{
				final Point p = bdv.getBdvHandle().getViewerPanel().getDisplay().getMousePosition();

				final AffineTransform3D viewerTransform = viewer.state().getViewerTransform();

				final double[] tmp = new double[] { p.x, p.y, 0 };
				viewerTransform.applyInverse(tmp, tmp);

				System.out.println( "adding at: " + p + ", which is " + Util.printCoordinates( tmp ) );

				points.add( tmp );

				viewer.requestRepaint();
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ctrl A" );
		}
	}

	public class DeletePoint extends AbstractNamedAction
	{
		private static final long serialVersionUID = 3640052275162419689L;

		public DeletePoint() { super( "Delete point" ); }

		@Override
		public void actionPerformed(ActionEvent e)
		{
			// a point is highlighted
			if ( pointId >= 0)
			{
				points.remove( pointId );
				bdv.getBdvHandle().getViewerPanel().requestRepaint();
			}
			else
			{
				System.out.println( "no point highlighted, cannot delete." );
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ctrl D" );
		}
	}

	public class JumpToPoint extends AbstractNamedAction
	{
		private static final long serialVersionUID = 3640052275162419689L;

		public JumpToPoint() { super( "Jump to point" ); }

		private ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();

		@Override
		public void actionPerformed(ActionEvent e)
		{
			// a point is highlighted
			if ( pointId >= 0)
			{
				synchronized (viewer) {

					viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

					final AffineTransform3D viewerTransform = viewer.state().getViewerTransform();

					final double[] tStart =
							new double[]{
									viewerTransform.get(0, 3),
									viewerTransform.get(1, 3),
									viewerTransform.get(2, 3)};

					final double[] tEnd = tStart.clone();
					tEnd[ 2 ] -= transformedPoints.get( pointId )[ 2 ];

					viewer.setTransformAnimator(new TranslationTransformAnimator(viewerTransform, tStart, tEnd, 300));

					viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				}
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ctrl J" );
		}
	}

	protected static class TranslationTransformAnimator extends AbstractTransformAnimator {

		final AffineTransform3D viewerTransform;
		final double[] tStart;
		final double[] tEnd;

		public TranslationTransformAnimator(
				final AffineTransform3D viewerTransform,
				final double[] tStart,
				final double[] tEnd,
				final long duration) {

			super(duration);

			this.viewerTransform = viewerTransform;
			this.tStart = tStart;
			this.tEnd = tEnd;
		}

		@Override
		public AffineTransform3D get(final double t) {

			final AffineTransform3D transform = viewerTransform.copy();

			transform.set(tStart[0] * (1.0 - t) + tEnd[0] * t, 0, 3);
			transform.set(tStart[1] * (1.0 - t) + tEnd[1] * t, 1, 3);
			transform.set(tStart[2] * (1.0 - t) + tEnd[2] * t, 2, 3);

			return transform;
		}

	}

	public static void main( final String[] args )
	{
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		final Random random = new Random();

		final Img< UnsignedByteType > img = ArrayImgs.unsignedBytes( 100, 100, 50 );
		img.forEach( t -> t.set( random.nextInt( 128 ) ) );

		final AffineTransform3D imageTransform = new AffineTransform3D();
		imageTransform.set( 2, 2, 2 ); //anisotropic in z
		final Bdv bdv = BdvFunctions.show( img, "image", BdvOptions.options().sourceTransform( imageTransform ) );

		InteractiveSegmentedLine line = new InteractiveSegmentedLine( bdv );
		line.test();
		
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
