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
public class OptimizeSeriesTransform
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
	
	private OptimizeSeriesTransform() {}
	
	public static void main( final String[] args )
	{
		
		/* collect all pairs of slices for which a model could be found */

		counter.set( 0 );
		int numFailures = 0;

		for ( int i = 0; i < stack.getSize(); ++i )
		{
			final ArrayList< Thread > threads = new ArrayList< Thread >( p.maxNumThreads );

			final int sliceA = i;
			final int range = Math.min( stack.getSize(), i + p.maxNumNeighbors + 1 );

J:				for ( int j = i + 1; j < range; )
			{
				final int numThreads = Math.min( p.maxNumThreads, range - j );
				final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > models =
					new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >( numThreads );

				for ( int k = 0; k < numThreads; ++k )
					models.add( null );

				for ( int t = 0;  t < numThreads && j < range; ++t, ++j )
				{
					final int ti = t;
					final int sliceB = j;

					final Thread thread = new Thread()
					{
						@Override
						public void run()
						{
							IJ.showProgress( sliceA, stack.getSize() - 1 );

							IJ.log( "matching " + sliceB + " -> " + sliceA + "..." );

							ArrayList< PointMatch > candidates = null;
							final String path = p.outputPath + String.format( "%05d", sliceB ) + "-" + String.format( "%05d", sliceA ) + ".pointmatches";
							if ( !p.clearCache )
								candidates = deserializePointMatches( p, path );

							if ( null == candidates )
							{
								final ArrayList< Feature > fs1 = deserializeFeatures( p.sift, p.outputPath + String.format( "%05d", sliceA ) + ".features" );
								final ArrayList< Feature > fs2 = deserializeFeatures( p.sift, p.outputPath + String.format( "%05d", sliceB ) + ".features" );
								candidates = new ArrayList< PointMatch >( FloatArray2DSIFT.createMatches( fs2, fs1, p.rod ) );

								if ( !serializePointMatches( p, candidates, path ) )
									IJ.log( "Could not store point matches!" );
							}

							final AbstractModel< ? > model = createModel( p.modelIndex );
							if ( model == null ) return;

							final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();

							boolean modelFound;
							boolean again = false;
							try
							{
								do
								{
									modelFound = model.filterRansac(
											candidates,
											inliers,
											1000,
											p.maxEpsilon,
											p.minInlierRatio,
											p.minNumInliers,
											3 );
									if ( modelFound && p.rejectIdentity )
									{
										final ArrayList< Point > points = new ArrayList< Point >();
										PointMatch.sourcePoints( inliers, points );
										if ( Transforms.isIdentity( model, points, p.identityTolerance ) )
										{
											IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
											candidates.removeAll( inliers );
											inliers.clear();
											again = true;
										}
									}
								}
								while ( again );
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

				try
				{
					for ( final Thread thread : threads )
						thread.join();
				}
				catch ( final InterruptedException e )
				{
					IJ.log( "Establishing feature correspondences interrupted." );
					for ( final Thread thread : threads )
						thread.interrupt();
					try
					{
						for ( final Thread thread : threads )
							thread.join();
					}
					catch ( final InterruptedException f ) {}
					return;
				}

				threads.clear();

				/* collect successfully matches pairs and break the search on gaps */
				for ( int t = 0; t < models.size(); ++t )
				{
					final Triple< Integer, Integer, AbstractModel< ? > > pair = models.get( t );
					if ( pair == null )
					{
						if ( ++numFailures > p.maxNumFailures )
							break J;
					}
					else
					{
						numFailures = 0;
						pairs.add( pair );
					}
				}
			}
		}
		
		
	}
	
}
