package org.janelia.render.client.solver.interactive;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.ActionMap;

import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.viewer.ViewerPanel;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class DragPointsBdv extends MinimalInteractiveBdvOverlay< List< double[] > >
{
	protected final List< double[] > points;
	protected final List< double[] > transformedPoints = new ArrayList<>();

	protected double SNAP_Z = 1;

	protected int HANDLE_RADIUS = 5;
	protected int HANDLE_RADIUS_SELECTED = 10;

	protected Color backColor = new Color( 0x00994499 );
	protected Color hitColor = Color.RED;
	protected Color frontColor = Color.GREEN;

	public DragPointsBdv( final Bdv bdv, final List< double[] > points)
	{
		super(bdv);

		MAP = "drag-point"; 
		BLOCKING_MAP = "drag-point-blocking";
		TOGGLE_EDITOR_KEYS = new String[] { "button1" }; 

		this.points = points;

		behaviours.behaviour( new DragPointBehaviour(), "edit points", TOGGLE_EDITOR_KEYS );

		new AddPoint().register(ksActionMap, ksKeyStrokeAdder);
		installKeyStrokes();
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

		for ( int i = 0; i < transformedPoints.size(); ++i )
			drawPoint( graphics, transformedPoints.get( i ), i==highLightedObjectId );		
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

	@Override
	protected MouseMotionAdapter setUpMouseMotionAdapter() { return new PointHighlighter( 5 ); }

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
					setHighlightedId( i );
					return;
				}
			}
			setHighlightedId( -1 );
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
			if ( highLightedObjectId >= 0 )
			{
				moving = true;
				movintPointId = highLightedObjectId;
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
	public class AddPoint extends AbstractNamedAction
	{
		private static final long serialVersionUID = 3640052275162419689L;

		public AddPoint() { super( "Add point" ); }

		private ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();

		@Override
		public void actionPerformed(ActionEvent e)
		{
			// a point is highlighted
			if ( highLightedObjectId >= 0)
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

		DragPointsBdv p = new DragPointsBdv( bdv, points );
		points = p.getResult();
	}

}
