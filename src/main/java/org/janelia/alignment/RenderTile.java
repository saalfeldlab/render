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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import javax.imageio.ImageIO;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
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

        @Parameter( names = "--res", description = " Mesh resolution, specified by the desired size of a triangle in pixels", required = false )
        public int res = 64;
        
        @Parameter( names = "--in", description = "Path to the input image if any", required = false )
        public String in;
        
        @Parameter( names = "--out", description = "Path to the output image", required = true )
        public String out;
        
        @Parameter( names = "--x", description = "Target image left coordinate", required = false )
        public long x = 0;
        
        @Parameter( names = "--y", description = "Target image top coordinate", required = false )
        public long y = 0;
        
        @Parameter( names = "--width", description = "Target image width", required = false )
        public int width = 256;
        
        @Parameter( names = "--height", description = "Target image height", required = false )
        public int height = 256;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
	}
	
	private RenderTile() {}
	
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
	
	final static public void render(
			final TileSpec[] tileSpecs,
			final BufferedImage targetImage,
			final double x,
			final double y,
			final double triangleSize )
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
				final TranslationModel2D offset = new TranslationModel2D();
				offset.set( -( float )x, -( float )y );
				ctl.add( offset );
				
				/* estiamte average scale */
				final double scale = Utils.sampleAverageScale( ctl, ip.getWidth(), ip.getHeight(), triangleSize );
				final int mipmapLevel = Utils.bestMipmapLevel( scale );
				
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
	
	public static void main( final String[] args )
	{
//		new ImageJ();
		
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
        	e.printStackTrace();
            final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar org.janelia.alignment.RenderTile" );
        	jc.usage(); 
        	return;
        }
		
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
		
		render( tileSpecs, targetImage, params.x, params.y, params.res );
		
		/* save the modified image */
		Utils.saveImage( targetImage, params.out, params.out.substring( params.out.lastIndexOf( '.' ) + 1 ) );
		
//		new ImagePlus( params.out ).show();
	}
}
