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

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TransformMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.ij.SIFT;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
public class ComputeSiftFeatures
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--url", description = "URL to JSON tile spec", required = true )
        private String url;

        @Parameter( names = "--index", description = "Tile index", required = false )
        private int index = 0;
        
        @Parameter( names = "--targetPath", description = "Path to the target image if any", required = true )
        public String targetPath = null;
        
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
	
	
	
	final static public ImagePlus openImagePlus( final String pathString )
	{
		final ImagePlus imp = new Opener().openImage( pathString );
		return imp;
	}
	
	final static public ImagePlus openImagePlusUrl( final String urlString )
	{
		final ImagePlus imp = new Opener().openURL( imageJUrl( urlString ) );
		return imp;
	}
	
	final static public BufferedImage openImageUrl( final String urlString )
	{
		BufferedImage image;
		try
		{
			final URL url = new URL( urlString );
			final BufferedImage imageTemp = ImageIO.read( url );
			
			/* This gymnastic is necessary to get reproducible gray
			 * values, just opening a JPG or PNG, even when saved by
			 * ImageIO, and grabbing its pixels results in gray values
			 * with a non-matching gamma transfer function, I cannot tell
			 * why... */
		    image = new BufferedImage( imageTemp.getWidth(), imageTemp.getHeight(), BufferedImage.TYPE_INT_ARGB );
			image.createGraphics().drawImage( imageTemp, 0, 0, null );
		}
		catch ( final Exception e )
		{
			try
			{
				final ImagePlus imp = openImagePlusUrl( urlString );
				if ( imp != null )
				{
					image = imp.getBufferedImage();
				}
				else image = null;
			}
			catch ( final Exception f )
			{
				image = null;
			}
		}
		return image;
	}
	
	final static public BufferedImage openImage( final String path )
	{
		BufferedImage image = null;
		try
		{
			final File file = new File( path );
			if ( file.exists() )
			{
				final BufferedImage jpg = ImageIO.read( file );
				
				/* This gymnastic is necessary to get reproducible gray
				 * values, just opening a JPG or PNG, even when saved by
				 * ImageIO, and grabbing its pixels results in gray values
				 * with a non-matching gamma transfer function, I cannot tell
				 * why... */
			    image = new BufferedImage( jpg.getWidth(), jpg.getHeight(), BufferedImage.TYPE_INT_ARGB );
				image.createGraphics().drawImage( jpg, 0, 0, null );
			}
		}
		catch ( final Exception e )
		{
			try
			{
				final ImagePlus imp = openImagePlus( path );
				if ( imp != null )
				{
					image = imp.getBufferedImage();
				}
				else image = null;
			}
			catch ( final Exception f )
			{
				image = null;
			}
		}
		return image;
	}
	
	/**
	 * If a URL starts with "file:", replace "file:" with "" because ImageJ wouldn't understand it otherwise
	 * @return
	 */
	final static private String imageJUrl( final String urlString )
	{
		return urlString.replace( "^file:", "" );
	}
	
	private ComputeSiftFeatures() {}
	
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
						
		List< FeatureSpec > feature_data = new ArrayList< FeatureSpec >();
		
		TileSpec ts = tileSpecs[params.index];
		
		/* load image TODO use Bioformats for strange formats */
		final ImagePlus imp = openImagePlus( ts.imageUrl.replaceFirst("file:///", "").replaceFirst("file://", "").replaceFirst("file:/", "") );
		if ( imp == null )
			System.err.println( "Failed to load image '" + ts.imageUrl + "'." );
		else
		{
			/* calculate sift features for the image or sub-region */
			System.out.println( "Calculating SIFT features for image '" + ts.imageUrl + "'." );
			FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();			
			FloatArray2DSIFT sift = new FloatArray2DSIFT(siftParam);
			SIFT ijSIFT = new SIFT(sift);
							
			final List< Feature > fs = new ArrayList< Feature >();
			ijSIFT.extractFeatures( imp.getProcessor(), fs );
			
			feature_data.add(new FeatureSpec(ts.imageUrl, fs));
		}
		
		try {
			Writer writer = new FileWriter(params.targetPath);
	        //Gson gson = new GsonBuilder().create();
	        Gson gson = new GsonBuilder().setPrettyPrinting().create();
	        gson.toJson(feature_data, writer);
	        writer.close();
	    }
		catch ( final IOException e )
		{
			System.err.println( "Error writing JSON file: " + params.targetPath );
			e.printStackTrace( System.err );
		}
	}
}
