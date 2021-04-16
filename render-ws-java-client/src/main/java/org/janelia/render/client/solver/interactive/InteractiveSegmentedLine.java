package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class InteractiveSegmentedLine extends VisualizeSegmentedLine
{
	private static final String SEGMENTED_LINE_MAP = "segmented-line";
	private static final String BLOCKING_MAP = "segmented-line-blocking";
	private static final String SEGMENTED_LINE_TOGGLE_EDITOR = "edit segmented-line";
	private static final String[] SEGMENTED_LINE_TOGGLE_EDITOR_KEYS = new String[] { "button1" };//, "ctrl K" };

	private int pointId = -1;
	private PointHighlighter pointHighLighter;
	private final TriggerBehaviourBindings triggerbindings;
	private final Behaviours behaviours;
	private final BehaviourMap blockMap;

	// for notification when the user pressed ESC or ENTER
	private final Object monitor = new Object();

	public InteractiveSegmentedLine( final Bdv bdv )
	{
		this( bdv, new ArrayList<>() );
	}

	public InteractiveSegmentedLine( final Bdv bdv, final List< double[] > points )
	{
		super( bdv, points );

		this.triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		this.pointHighLighter = new PointHighlighter( 10 );

		/*
		 * Create DragPointBehaviour
		 * https://github.com/scijava/ui-behaviour/blob/master/src/test/java/org/scijava/ui/behaviour/UsageExample.java
		 */
		behaviours = new Behaviours( new InputTriggerConfig(), "bdv" );
		behaviours.behaviour( new DragPointBehaviour(), SEGMENTED_LINE_TOGGLE_EDITOR, SEGMENTED_LINE_TOGGLE_EDITOR_KEYS );

		/*
		 * setting up keystrokes
		 * 
		 * https://github.com/scijava/ui-behaviour/wiki/InputTrigger-syntax
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
		new Cancel().register(ksActionMap, ksKeyStrokeAdder);
		new Confirm().register(ksActionMap, ksKeyStrokeAdder);

		bdv.getBdvHandle().getKeybindings().addActionMap("persistence", ksActionMap);
		bdv.getBdvHandle().getKeybindings().addInputMap("persistence", ksInputMap);

		/*
		 * Create BehaviourMap to block behaviours interfering with
		 * DragPointBehaviour. The block map is only active while a corner
		 * is highlighted.
		 */
		blockMap = new BehaviourMap();
	}

	public void install()
	{
		super.install();
		//bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer( this );
		//bdv.getBdvHandle().getViewerPanel().addRenderTransformListener( this );
		bdv.getBdvHandle().getViewerPanel().getDisplay().addHandler( pointHighLighter );

		//refreshBlockMap();
		//updateEditability();
		buildBlockMap();
		behaviours.install( triggerbindings, SEGMENTED_LINE_MAP );
	}

	public void uninstall()
	{
		super.uninstall();
		//bdv.getBdvHandle().getViewerPanel().getDisplay().removeOverlayRenderer( this );
		//bdv.getBdvHandle().getViewerPanel().removeTransformListener( this );
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
	protected void drawPoints( final Graphics2D graphics )
	{
		for ( int i = 0; i < transformedPoints.size(); ++i )
			drawPoint( graphics, transformedPoints.get( i ), i==pointId );
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

	public List< double[] > getResult()
	{
		synchronized ( monitor )
		{
			install();

			while( true )
			{
				try
				{
					monitor.wait();
					break;
				}
				catch ( final InterruptedException e )
				{
					e.printStackTrace();
				}
			}

			uninstall();
			bdv.getBdvHandle().getViewerPanel().requestRepaint();
		}

		return points;
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

	public class Confirm extends AbstractNamedAction
	{
		private static final long serialVersionUID = -8388751240134830158L;

		public Confirm() { super( "Confirm" ); }

		@Override
		public void actionPerformed(ActionEvent e)
		{
			synchronized ( monitor )
			{
				monitor.notifyAll();
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ENTER" );
		}
	}

	public class Cancel extends AbstractNamedAction
	{
		private static final long serialVersionUID = 7966320561651920538L;

		public Cancel() { super( "Cancel" ); }

		@Override
		public void actionPerformed(ActionEvent e)
		{
			synchronized ( monitor )
			{
				points.clear();
				monitor.notifyAll();
			}
		}

		public void register(ActionMap ksActionMap, KeyStrokeAdder ksKeyStrokeAdder ) {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), "ESCAPE" );
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

				final double[] tmp = new double[] { p.x, p.y, 0 };
				viewerTransform.applyInverse(tmp, tmp);

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

		List< double[] > points = new ArrayList<>();

		points.add( new double[] { 10, 10 ,10 } );
		points.add( new double[] { 50, 20 ,30 } );
		points.add( new double[] { 90, 90 ,90 } );

		InteractiveSegmentedLine line = new InteractiveSegmentedLine( bdv, points );
		points = line.getResult();

		if ( points != null && points.size() > 0 )
			new VisualizeSegmentedLine( bdv, points, Color.yellow, Color.yellow.darker(), null ).install();
	}
}
