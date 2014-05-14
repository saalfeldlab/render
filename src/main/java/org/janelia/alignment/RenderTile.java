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

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import javax.imageio.ImageIO;

import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.TransformMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Render an image tile as an ARGB TIFF or PNG image.  The result image covers
 * the minimal bounding box required to render the transformed image.
 * 
 * <p>
 * Start the renderer with the absolute path of the the JSON file that contains
 * all specifications of the tile, and the resolution of the mesh used for
 * rendering.  E.g.
 * </p>
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
 *      Mesh resolution
 *      Default: 0
 *       --targetPath
 *      Path to the target image if any
 *       --threads
 *      Number of threads to be used
 *      Default: 48
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
 *   --targetPath "/absolute/path/to/output" \
 *   --x 16536
 *   --y 32
 *   --res 64</pre>
 * 
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class RenderTile
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--url", description = "URL to JSON tile spec", required = true )
        private String url;

        @Parameter( names = "--res", description = "Mesh resolution", required = true )
        private int res;
        
        @Parameter( names = "--targetPath", description = "Path to the target image if any", required = false )
        public String targetPath = null;
        
        @Parameter( names = "--x", description = "Target image left coordinate", required = true )
        private long x;
        
        @Parameter( names = "--y", description = "Target image top coordinate", required = true )
        private long y;
        
        @Parameter( names = "--width", description = "Target image width", required = false )
        public int width = 256;
        
        @Parameter( names = "--height", description = "Target image height", required = false )
        public int height = 256;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
	}
	
	private RenderTile() {}
	
	/**
	 * Combine a 0x??rgb int[] raster and an unsigned byte[] alpha channel into
	 * a 0xargb int[] raster.  The operation is perfomed in place on the int[]
	 * raster.
	 */
	final static public void combineARGB( final int[] rgb, final byte[] a )
	{
		for ( int i = 0; i < rgb.length; ++i )
		{
			rgb[ i ] &= 0x00ffffff;
			rgb[ i ] |= ( a[ i ] & 0xff ) << 24;
		}
	}
	
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
	
	public static void main( final String[] args )
	{
		final Params params = new Params();
		try
        {
			final JCommander jc = new JCommander( params, args );
        	if ( params.help )
            {
                jc.usage();
                return;
            }
        }
        catch ( final Exception e )
        {
        	final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar org.janelia.alignment.RenderTile" );
        	jc.usage(); 
        	return;
        }
		
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
		final ColorProcessor cpTarget;
		if ( params.targetPath != null )
		{
			final ImagePlus impTarget = new ImagePlus( params.targetPath );
			if ( impTarget != null )
				cpTarget = impTarget.getProcessor().convertToColorProcessor();
			else
				cpTarget = new ColorProcessor( params.width, params.height );
		}
		else
			cpTarget = new ColorProcessor( params.width, params.height );
		
		for ( final TileSpec ts : tileSpecs )
		{
			final ImagePlus imp = new ImagePlus( ts.imageUrl );
			if ( imp == null )
				System.err.println( "Failed to load image '" + ts.imageUrl + "'." );
			else
			{
				final ImageProcessor ip = imp.getProcessor();
				ip.setMinAndMax( ts.minIntensity, ts.maxIntensity );
				final ColorProcessor cp = ip.convertToColorProcessor();
				
				final ByteProcessor bpMask;
				if ( ts.maskUrl != null )
				{
					final ImagePlus impMask = new ImagePlus( ts.maskUrl );
					if ( impMask == null )
					{
						System.err.println( "Failed to load mask '" + ts.maskUrl + "'." );
						bpMask = null;
					}
					else
						bpMask = impMask.getProcessor().convertToByteProcessor();
				}
				else
				{
					bpMask = null;
				}
				
				final CoordinateTransformList< CoordinateTransform > ctl = ts.createTransformList();
				final TranslationModel2D offset = new TranslationModel2D();
				offset.set( -params.x, -params.y );
				ctl.add( offset );
				final TransformMesh mesh = new TransformMesh( params.res, ip.getWidth(), ip.getHeight() );
				if ( bpMask == null )
				{
					final TransformMeshMapping< TransformMesh > mapping = new TransformMeshMapping< TransformMesh >( mesh );
					mapping.mapInterpolated( cp, cpTarget, params.numThreads );
				}
				else
				{
					final ImageProcessorWithMasks source = new ImageProcessorWithMasks( cp, bpMask, null );
					final ImageProcessorWithMasks target = new ImageProcessorWithMasks( cpTarget, null, null );
					final TransformMeshMappingWithMasks< TransformMesh > mapping = new TransformMeshMappingWithMasks< TransformMesh >( mesh );
					mapping.mapInterpolated( source, target );
				}
			}
		}
	}
}
