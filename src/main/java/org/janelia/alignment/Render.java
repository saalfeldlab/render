/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import javax.imageio.ImageIO;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Render a set of image tile as an ARGB image.
 * 
 * <pre>
 * Usage: java [-options] -cp render.jar org.janelia.alignment.RenderTile [options]
 * Options:
 *       --height
 *      Target image height
 *      Default: 256
 *       --help
 *      Display this note
 *      Default: false
 * *     --res
 *      Mesh resolution, specified by the desired size of a triangle in pixels
 *       --in
 *      Path to the input image if any
 *       --out
 *      Path to the output image
 *       --threads
 *      Number of threads to be used
 *      Default: number of available CPUs
 * *     --url
 *      URL to JSON tile spec
 *       --width
 *      Target image width
 *      Default: 256
 * *     --x
 *      Target image left coordinate
 *      Default: 0
 * *     --y
 *      Target image top coordinate
 *      Default: 0
 * </pre>
 * <p>E.g.:</p>
 * <pre>java -cp render.jar org.janelia.alignment.RenderTile \
 *   --url "file://absolute/path/to/tile-spec.json" \
 *   --out "/absolute/path/to/output.png" \
 *   --x 16536
 *   --y 32
 *   --width 1024
 *   --height 1024
 *   --res 64</pre>
 * 
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Render
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--url", description = "URL to JSON tile spec", required = true )
        public String url;

        @Parameter( names = "--res", description = " Mesh resolution, specified by the desired size of a triangle in pixels", required = false )
        public int res = 64;
        
        @Parameter( names = "--in", description = "Path to the input image if any", required = false )
        public String in;
        
        @Parameter( names = "--out", description = "Path to the output image", required = true )
        public String out;
        
        @Parameter( names = "--x", description = "Target image left coordinate", required = false )
        public double x = 0;
        
        @Parameter( names = "--y", description = "Target image top coordinate", required = false )
        public double y = 0;
        
        @Parameter( names = "--width", description = "Target image width", required = false )
        public int width = 256;
        
        @Parameter( names = "--height", description = "Target image height", required = false )
        public int height = 256;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
        
        @Parameter( names = "--scale", description = "scale factor applied to the target image (overrides --mipmap_level)", required = false )
        public double scale = -Double.NaN;
        
        @Parameter( names = "--mipmap_level", description = "scale level in a mipmap pyramid", required = false )
        public int mipmapLevel = 0;
        
        @Parameter( names = "--area_offset", description = "scale level in a mipmap pyramid", required = false )
        public boolean areaOffset = false;
	}
	
	private Render() {}
	
	/**
	 * Create a {@link BufferedImage} from an existing pixel array.  Make sure
	 * that pixels.length == width * height.
	 * 
	 * @param pixels
	 * @param width
	 * @param height
	 * 
	 * @return BufferedImage
	 */
	final static public BufferedImage createARGBImage( final int[] pixels, final int width, final int height )
	{
		assert( pixels.length == width * height ) : "The number of pixels is not equal to width * height.";
		
		final BufferedImage image = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
		final WritableRaster raster = image.getRaster();
		raster.setDataElements( 0, 0, width, height, pixels );
		return image;
	}
	
	
	final static void saveImage( final BufferedImage image, final String path, final String format ) throws IOException
	{
		ImageIO.write( image, format, new File( path ) );
	}
	
	final static Params parseParams( final String[] args )
	{
		final Params params = new Params();
		try
        {
			final JCommander jc = new JCommander( params, args );
        	if ( params.help )
            {
        		jc.usage();
                return null;
            }
        }
        catch ( final Exception e )
        {
        	e.printStackTrace();
            final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar + " + Render.class.getCanonicalName() );
        	jc.usage(); 
        	return null;
        }
		
		/* process params */
		if ( Double.isNaN( params.scale ) )
			params.scale = 1.0 / ( 1L << params.mipmapLevel );
		
		params.width *= params.scale;
		params.height *= params.scale;
		
		System.out.println( params.areaOffset );
		
		return params;
	}
	
	final static public void render(
			final TileSpec[] tileSpecs,
			final BufferedImage targetImage,
			final double x,
			final double y,
			final double triangleSize,
			final double scale,
			final boolean areaOffset )
	{
		final Graphics2D targetGraphics = targetImage.createGraphics();
		
		for ( final TileSpec ts : tileSpecs )
		{
			/* load image TODO use Bioformats for strange formats */
			final ImagePlus imp = Utils.openImagePlusUrl( ts.imageUrl );
			if ( imp == null )
				System.err.println( "Failed to load image '" + ts.imageUrl + "'." );
			else
			{
				final ImageProcessor ip = imp.getProcessor();
				final ImageProcessor tp = ip.createProcessor( targetImage.getWidth(), targetImage.getHeight() );
				
				/* assemble coordinate transformations and add bounding box offset */
				final CoordinateTransformList< CoordinateTransform > ctl = ts.createTransformList();
				final AffineModel2D scaleAndOffset = new AffineModel2D();
				System.out.println( scale );
				if ( areaOffset )
				{
					final double offset = ( 1 - scale ) * 0.5;
					scaleAndOffset.set( ( float )scale, 0, 0, ( float )scale, -( float )( x * scale + offset ), -( float )( y * scale + offset ) );
				}
				else
					scaleAndOffset.set( ( float )scale, 0, 0, ( float )scale, -( float )( x * scale ), -( float )( y * scale ) );
				
				ctl.add( scaleAndOffset );
				
				/* estiamte average scale */
				final double s = Utils.sampleAverageScale( ctl, ip.getWidth(), ip.getHeight(), triangleSize );
				final int mipmapLevel = Utils.bestMipmapLevel( s );
				
				/* create according mipmap level */
				final ImageProcessor ipMipmap = Downsampler.downsampleImageProcessor( ip, mipmapLevel );
				
				/* open mask */
				final ByteProcessor bpMaskSource;
				final ByteProcessor bpMaskTarget;
				if ( ts.maskUrl != null )
				{
					final ImagePlus impMask = Utils.openImagePlusUrl( ts.maskUrl );
					if ( impMask == null )
					{
						System.err.println( "Failed to load mask '" + ts.maskUrl + "'." );
						bpMaskSource = null;
						bpMaskTarget = null;
					}
					else
					{
						/* create according mipmap level */
						bpMaskSource = Downsampler.downsampleByteProcessor( impMask.getProcessor().convertToByteProcessor(), mipmapLevel );
						bpMaskTarget = new ByteProcessor( tp.getWidth(), tp.getHeight() );
					}
				}
				else
				{
					bpMaskSource = null;
					bpMaskTarget = null;
				}
				
				
				/* attach mipmap transformation */
				final CoordinateTransformList< CoordinateTransform > ctlMipmap = new CoordinateTransformList< CoordinateTransform >();
				ctlMipmap.add( Utils.createScaleLevelTransform( mipmapLevel ) );
				ctlMipmap.add( ctl );
				
				/* create mesh */
				final CoordinateTransformMesh mesh = new CoordinateTransformMesh( ctlMipmap,  ( int )( ip.getWidth() / triangleSize + 0.5 ), ipMipmap.getWidth(), ipMipmap.getHeight() );
				
				final ImageProcessorWithMasks source = new ImageProcessorWithMasks( ipMipmap, bpMaskSource, null );
				final ImageProcessorWithMasks target = new ImageProcessorWithMasks( tp, bpMaskTarget, null );
				final TransformMeshMappingWithMasks< TransformMesh > mapping = new TransformMeshMappingWithMasks< TransformMesh >( mesh );
				mapping.mapInterpolated( source, target );
				
				/* convert to 24bit RGB */
				tp.setMinAndMax( ts.minIntensity, ts.maxIntensity );
				final ColorProcessor cp = tp.convertToColorProcessor();
				
				final int[] cpPixels = ( int[] )cp.getPixels();
				final byte[] alphaPixels;
				
				
				/* set alpha channel */
				if ( bpMaskTarget != null )
					alphaPixels = ( byte[] )bpMaskTarget.getPixels();
				else
					alphaPixels = ( byte[] )target.outside.getPixels();

				for ( int i = 0; i < cpPixels.length; ++i )
					cpPixels[ i ] &= 0x00ffffff | ( alphaPixels[ i ] << 24 );

				final BufferedImage image = new BufferedImage( cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB );
				final WritableRaster raster = image.getRaster();
				raster.setDataElements( 0, 0, cp.getWidth(), cp.getHeight(), cpPixels );
				
				targetGraphics.drawImage( image, 0, 0, null );
			}
		}
	}
	
	final static public void render(
			final TileSpec[] tileSpecs,
			final BufferedImage targetImage,
			final double x,
			final double y,
			final double triangleSize )
	{
		render( tileSpecs, targetImage, x, y, triangleSize, 1.0, false );
	}
	
	final static public BufferedImage render(
			final TileSpec[] tileSpecs,
			final double x,
			final double y,
			final int width,
			final int height,
			final double triangleSize,
			final double scale,
			final boolean areaOffset )
	{
		final BufferedImage image = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
		render( tileSpecs, image, x, y, triangleSize, scale, areaOffset );
		return image;
	}
	
	final static public BufferedImage render(
			final TileSpec[] tileSpecs,
			final double x,
			final double y,
			final int width,
			final int height,
			final double triangleSize )
	{
		final BufferedImage image = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
		render( tileSpecs, image, x, y, triangleSize, 1.0, false );
		return image;
	}
	
	public static void main( final String[] args )
	{
		new ImageJ();
		
		final Params params = parseParams( args );
		
		if ( params == null )
			return;
		
		
		/* open tilespec */
		final URL url;
		final TileSpec[] tileSpecs;
		try
		{
			final Gson gson = new Gson();
			url = new URL( params.url );
			tileSpecs = gson.fromJson( new InputStreamReader( url.openStream() ), TileSpec[].class );
		}
		catch ( final MalformedURLException e )
		{
			System.err.println( "URL malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final JsonSyntaxException e )
		{
			System.err.println( "JSON syntax malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}
		
		/* open or create target image */
		BufferedImage targetImage = null;
		if ( params.in != null )
		{
			targetImage = Utils.openImage( params.in );
			if ( targetImage != null )
			{
				params.width = targetImage.getWidth();
				params.height = targetImage.getHeight();
			}
		}
		if ( targetImage == null )
			targetImage = new BufferedImage( params.width, params.height, BufferedImage.TYPE_INT_ARGB );
		
		render( tileSpecs, targetImage, params.x, params.y, params.res, params.scale, params.areaOffset );
		ColorProcessor cp = new ColorProcessor( render( tileSpecs, params.x, params.y, ( int )( params.width / params.scale ), ( int )( params.height / params.scale ), params.res, 1.0, false ) );
		cp = Downsampler.downsampleColorProcessor( cp, params.mipmapLevel );
		new ImagePlus( "downsampled", cp ).show();
		new ImagePlus( "result", new ColorProcessor( targetImage ) ).show();
		
		/* save the modified image */
		Utils.saveImage( targetImage, params.out, params.out.substring( params.out.lastIndexOf( '.' ) + 1 ) );
		
		new ImagePlus( params.out ).show();
	}
}
