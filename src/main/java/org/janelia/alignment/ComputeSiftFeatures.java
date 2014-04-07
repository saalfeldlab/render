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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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
 * 
 * @author Seymour Knowles-Barley
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
	
	private ComputeSiftFeatures() {}
	
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
		final ImagePlus imp = Utils.openImagePlus( ts.imageUrl.replaceFirst("file:///", "").replaceFirst("file://", "").replaceFirst("file:/", "") );
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
