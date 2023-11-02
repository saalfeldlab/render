package org.janelia.render.client.solver.interactive;

import bdv.util.Bdv;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import net.imglib2.realtransform.AffineTransform3D;

public abstract class MinimalBdvOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	protected final AffineTransform3D viewerTransform;
	protected final Bdv bdv;
	protected int canvasWidth = 0;
	protected int canvasHeight = 0;

	public MinimalBdvOverlay( final Bdv bdv )
	{
		this.bdv = bdv;
		this.viewerTransform = new AffineTransform3D();
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{
		this.canvasWidth = width;
		this.canvasHeight = height;
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
}
