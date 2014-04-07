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
import ij.process.FloatProcessor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import mpicbg.models.AbstractModel;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SpringMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.ij.blockmatching.BlockMatching;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * <pre>java -cp fijibento.jar org.janelia.alignment.MatchByMaxPMCC \
 *   --inputfile1 "tilespec1" \
 *   --inputfile2 "tilespec2" \
 * 
 * @author Seymour Knowles-Barley
 */
public class MatchByMaxPMCC
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--inputfile1", description = "First image or tilespec file", required = true )
        private String inputfile1;
        
        @Parameter( names = "--inputfile2", description = "Second image or tilespec file", required = true )
        private String inputfile2;
        
        @Parameter( names = "--targetPath", description = "Path for the output correspondences", required = true )
        public String targetPath;
        
        @Parameter( names = "--index1", description = "Image index within first tilespec file", required = false )
        public int index1 = 0;
        
        @Parameter( names = "--index2", description = "Image index within second tilespec file", required = false )
        public int index2 = 0;
        
        @Parameter( names = "--layerScale", description = "Layer scale", required = false )
        public float layerScale = 0.2f;
        
        @Parameter( names = "--searchRadius", description = "Search window radius", required = false )
        public int searchRadius = 200;
        
        @Parameter( names = "--blockRadius", description = "Matching block radius", required = false )
        public int blockRadius = 200;
                
        @Parameter( names = "--resolutionSpringMesh", description = "resolutionSpringMesh", required = false )
        public int resolutionSpringMesh = 32;
        
        @Parameter( names = "--minR", description = "minR", required = false )
        public float minR = 0.5f;
        
        @Parameter( names = "--maxCurvatureR", description = "maxCurvatureR", required = false )
        public float maxCurvatureR = 10f;
        
        @Parameter( names = "--rodR", description = "rodR", required = false )
        public float rodR = 0.9f;
        
        @Parameter( names = "--useLocalSmoothnessFilter", description = "useLocalSmoothnessFilter", required = false )
        public boolean useLocalSmoothnessFilter = true;
        
        @Parameter( names = "--localModelIndex", description = "localModelIndex", required = false )
        public int localModelIndex = 1;
        // 0 = "Translation", 1 = "Rigid", 2 = "Similarity", 3 = "Affine"
        
        @Parameter( names = "--localRegionSigma", description = "localRegionSigma", required = false )
        public float localRegionSigma = 200f;
        
        @Parameter( names = "--maxLocalEpsilon", description = "maxLocalEpsilon", required = false )
        public float maxLocalEpsilon = 100f;
        
        @Parameter( names = "--maxLocalTrust", description = "maxLocalTrust", required = false )
        public int maxLocalTrust = 3;
        
        @Parameter( names = "--maxNumNeighbors", description = "maxNumNeighbors", required = false )
        public float maxNumNeighbors = 3f;
        		
        @Parameter( names = "--stiffnessSpringMesh", description = "stiffnessSpringMesh", required = false )
        public float stiffnessSpringMesh = 0.1f;
		
        @Parameter( names = "--dampSpringMesh", description = "dampSpringMesh", required = false )
        public float dampSpringMesh = 0.9f;
		
        @Parameter( names = "--maxStretchSpringMesh", description = "maxStretchSpringMesh", required = false )
        public float maxStretchSpringMesh = 2000.0f;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
        
	}
	
	private MatchByMaxPMCC() {}
	
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
		
		/* open tilespec1 */
		final TileSpec[] tileSpecs1;
		final TileSpec[] tileSpecs2;
		try
		{
			final Gson gson = new Gson();
			URL url = new URL( params.inputfile1 );
			tileSpecs1 = gson.fromJson( new InputStreamReader( url.openStream() ), TileSpec[].class );
			url = new URL( params.inputfile2 );
			tileSpecs2 = gson.fromJson( new InputStreamReader( url.openStream() ), TileSpec[].class );
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
						
		TileSpec ts1 = tileSpecs1[params.index1];
		TileSpec ts2 = tileSpecs2[params.index2];
		
		final ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
		final ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

		/* load image TODO use Bioformats for strange formats */
		final ImagePlus imp1 = Utils.openImagePlus( ts1.imageUrl.replaceFirst("file:///", "").replaceFirst("file://", "").replaceFirst("file:/", "") );
		final ImagePlus imp2 = Utils.openImagePlus( ts2.imageUrl.replaceFirst("file:///", "").replaceFirst("file://", "").replaceFirst("file:/", "") );

		final SpringMesh m1 = Utils.getMesh( imp1.getWidth(), imp1.getHeight(), params.layerScale, params.resolutionSpringMesh, params.stiffnessSpringMesh, params.dampSpringMesh, params.maxStretchSpringMesh );
		final SpringMesh m2 = Utils.getMesh( imp2.getWidth(), imp2.getHeight(), params.layerScale, params.resolutionSpringMesh, params.stiffnessSpringMesh, params.dampSpringMesh, params.maxStretchSpringMesh );

		final ArrayList< Vertex > v1 = m1.getVertices();
		final ArrayList< Vertex > v2 = m2.getVertices();

		final CoordinateTransformList< CoordinateTransform > ctl1 = ts1.createTransformList();
		final CoordinateTransformList< CoordinateTransform > ctl2 = ts2.createTransformList();
		
		/* TODO: masks? */
		/* calculate block matches */

		final AbstractModel< ? > localSmoothnessFilterModel = Utils.createModel( params.localModelIndex );

		final FloatProcessor ip1 = ( FloatProcessor )imp1.getProcessor().convertToFloat().duplicate();
		final FloatProcessor ip2 = ( FloatProcessor )imp2.getProcessor().convertToFloat().duplicate();
		
		final int blockRadius = Math.max( 16, mpicbg.util.Util.roundPos( params.layerScale * params.blockRadius ) );
        final int searchRadius = Math.round( params.layerScale * params.searchRadius );

		final TranslationModel2D transform12 = (( TranslationModel2D )ctl1.get(0)).createInverse();
		transform12.concatenate( (( TranslationModel2D )( Object )ctl2.get(0)) );
		
		try{
			BlockMatching.matchByMaximalPMCC(
					ip1,
					ip2,
					null, //mask1
					null, //mask2
					1.0f, //Math.min( 1.0f, ( float )params.maxImageSize / ip1.getWidth() ),
					transform12,
					blockRadius,
					blockRadius,
					searchRadius,
					searchRadius,
					params.minR,
					params.rodR,
					params.maxCurvatureR,
					v1,
					pm12,
					new ErrorStatistic( 1 ) );
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}

		if ( params.useLocalSmoothnessFilter )
		{
			System.out.println( ts1.imageUrl + " > " + ts2.imageUrl + ": found " + pm12.size() + " correspondence candidates." );
			localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, params.localRegionSigma, params.maxLocalEpsilon, params.maxLocalTrust );
			System.out.println( ts1.imageUrl + " > " + ts2.imageUrl + ": " + pm12.size() + " candidates passed local smoothness filter." );
		}
		else
		{
			System.out.println( ts1.imageUrl + " > " + ts2.imageUrl + ": found " + pm12.size() + " correspondences." );
		}


		try{
		BlockMatching.matchByMaximalPMCC(
				ip2,
				ip1,
				null, //mask2
				null, //mask1
				1.0f, //Math.min( 1.0f, ( float )p.maxImageSize / ip2.getWidth() ),
				transform12.createInverse(),
				blockRadius,
				blockRadius,
				searchRadius,
				searchRadius,
				params.minR,
				params.rodR,
				params.maxCurvatureR,
				v2,
				pm21,
				new ErrorStatistic( 1 ) );
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}


		if ( params.useLocalSmoothnessFilter )
		{
			System.out.println( ts2.imageUrl + " > " + ts1.imageUrl + ": found " + pm21.size() + " correspondence candidates." );
			localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, params.localRegionSigma, params.maxLocalEpsilon, params.maxLocalTrust );
			System.out.println( ts2.imageUrl + " > " + ts1.imageUrl + ": " + pm21.size() + " candidates passed local smoothness filter." );
		}
		else
		{
			System.out.println( ts2.imageUrl + " > " + ts1.imageUrl + ": found " + pm21.size() + " correspondences." );
		}
		
		List< CorrespondenceSpec > corr_data = new ArrayList< CorrespondenceSpec >();
		
		// Remove Vertex (spring mesh) details from points
		final ArrayList< PointMatch > pm12_strip = new ArrayList< PointMatch >();
		final ArrayList< PointMatch > pm21_strip = new ArrayList< PointMatch >();
		for (PointMatch pm: pm12)
		{
			pm12_strip.add(new PointMatch(
					new Point(pm.getP1().getL(), pm.getP1().getW()),
					new Point(pm.getP2().getL(), pm.getP2().getW())));
		}
		for (PointMatch pm: pm21)
		{
			pm21_strip.add(new PointMatch(
					new Point(pm.getP1().getL(), pm.getP1().getW()),
					new Point(pm.getP2().getL(), pm.getP2().getW())));
		}

		// TODO: Export / Import master sprint mesh vertices no calculated  individually per tile (v1, v2).
		corr_data.add(new CorrespondenceSpec(
				tileSpecs1[ params.index1 ].imageUrl,
				tileSpecs2[ params.index2 ].imageUrl,
				pm12_strip));
		
		corr_data.add(new CorrespondenceSpec(
				tileSpecs2[ params.index2 ].imageUrl,
				tileSpecs1[ params.index1 ].imageUrl,
				pm21_strip));
					
		try {
			Writer writer = new FileWriter(params.targetPath);
	        //Gson gson = new GsonBuilder().create();
	        Gson gson = new GsonBuilder().setPrettyPrinting().create();
	        gson.toJson(corr_data, writer);
	        writer.close();
	    }
		catch ( final IOException e )
		{
			System.err.println( "Error writing JSON file: " + params.targetPath );
			e.printStackTrace( System.err );
		}
	}
	
}
