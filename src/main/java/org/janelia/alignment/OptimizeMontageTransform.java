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
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.SpringMesh;
import mpicbg.models.TransformMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.ij.blockmatching.BlockMatching;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * @author Seymour Knowles-Barley
 */
public class OptimizeMontageTransform
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--inputfile", description = "Correspondence list file", required = true )
        private String inputfile;
                        
        @Parameter( names = "--targetPath", description = "Path for the output correspondences", required = true )
        public String targetPath;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
        
	}
	
	final static public AbstractModel< ? > createModel( final int modelIndex )
	{
		switch ( modelIndex )
		{
		case 0:
			return new TranslationModel2D();
		case 1:
			return new RigidModel2D();
		case 2:
			return new SimilarityModel2D();
		case 3:
			return new AffineModel2D();
		case 4:
			return new HomographyModel2D();
		default:
			return null;
		}
	}
	
	private OptimizeMontageTransform() {}
	
	public static void main( final String[] args )
	{
		//TODO: Read in point matches
		
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
		
		/* collect all pairs of tiles for which a model could be found */
		final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > pairs = new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >();

		counter.set( 0 );

		for ( int i = 0; i < stack.getSize(); ++i )
		{
			final ArrayList< Thread > threads = new ArrayList< Thread >( p.maxNumThreads );

			final int sliceA = i;

			for ( int j = i + 1; j < stack.getSize(); )
			{
				final int numThreads = Math.min( p.maxNumThreads, stack.getSize() - j );
				final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > models =
					new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >( numThreads );

				for ( int k = 0; k < numThreads; ++k )
					models.add( null );

				for ( int t = 0;  t < p.maxNumThreads && j < stack.getSize(); ++t, ++j )
				{
					final int sliceB = j;
					final int ti = t;

					final Thread thread = new Thread()
					{
						@Override
						public void run()
						{
							IJ.showProgress( counter.getAndIncrement(), stack.getSize() - 1 );

							IJ.log( "matching " + sliceB + " -> " + sliceA + "..." );

							//String path = p.outputPath + stack.getSliceLabel( slice ) + ".pointmatches";
							final String path = p.outputPath + String.format( "%05d", sliceB ) + "-" + String.format( "%05d", sliceA ) + ".pointmatches";
							ArrayList< PointMatch > candidates = deserializePointMatches( p, path );

							if ( null == candidates )
							{
								//ArrayList< Feature > fs1 = deserializeFeatures( p.sift, p.outputPath + stack.getSliceLabel( slice - 1 ) + ".features" );
								final ArrayList< Feature > fs1 = deserializeFeatures( p.sift, p.outputPath + String.format( "%05d", sliceA ) + ".features" );
								//ArrayList< Feature > fs2 = deserializeFeatures( p.sift, p.outputPath + stack.getSliceLabel( slice ) + ".features" );
								final ArrayList< Feature > fs2 = deserializeFeatures( p.sift, p.outputPath + String.format( "%05d", sliceB ) + ".features" );
								candidates = new ArrayList< PointMatch >( FloatArray2DSIFT.createMatches( fs2, fs1, p.rod ) );

								if ( !serializePointMatches( p, candidates, path ) )
									IJ.log( "Could not store point matches!" );
							}

							AbstractModel< ? > model;
							switch ( p.modelIndex )
							{
							case 0:
								model = new TranslationModel2D();
								break;
							case 1:
								model = new RigidModel2D();
								break;
							case 2:
								model = new SimilarityModel2D();
								break;
							case 3:
								model = new AffineModel2D();
								break;
							case 4:
								model = new HomographyModel2D();
								break;
							default:
								return;
							}

							final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();

							boolean modelFound;
							try
							{
								modelFound = model.filterRansac(
										candidates,
										inliers,
										1000,
										p.maxEpsilon,
										p.minInlierRatio,
										p.minNumInliers );
							}
							catch ( final Exception e )
							{
								modelFound = false;
								System.err.println( e.getMessage() );
							}

							if ( modelFound )
							{
								IJ.log( sliceB + " -> " + sliceA + ": " + inliers.size() + " corresponding features with an average displacement of " + PointMatch.meanDistance( inliers ) + "px identified." );
								IJ.log( "Estimated transformation model: " + model );
								models.set( ti, new Triple< Integer, Integer, AbstractModel< ? > >( sliceA, sliceB, model ) );
							}
							else
							{
								IJ.log( sliceB + " -> " + sliceA + ": no correspondences found." );
								return;
							}
						}
					};
					threads.add( thread );
					thread.start();
				}

				for ( final Thread thread : threads )
				{
					thread.join();
				}

				/* collect successfully matches pairs */
				for ( int t = 0; t < models.size(); ++t )
				{
					final Triple< Integer, Integer, AbstractModel< ? > > pair = models.get( t );
					if ( pair != null )
						pairs.add( pair );
				}
			}

			IJ.showProgress( i, stack.getSize() - 1 );
		}
						

		// Export results
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
