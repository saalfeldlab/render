package org.janelia.render.client.solver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ScriptUtil;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import imglib.ops.operator.binary.Min;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class SolveTools
{
	private SolveTools() {}

	protected static AffineModel2D createAffine( final Affine2D< ? > model )
	{
		final AffineModel2D m = new AffineModel2D();
		m.set( model.createAffine() );

		return m;
	}

	protected static List< PointMatch > duplicate( List< PointMatch > pms )
	{
		final List< PointMatch > copy = new ArrayList<>();

		for ( final PointMatch pm : pms )
			copy.add( new PointMatch( pm.getP1().clone(), pm.getP2().clone(), pm.getWeight() ) );

		return copy;
	}

	public static List< PointMatch > createRelativePointMatches(
			final List< PointMatch > absolutePMs,
			final Model< ? > pModel,
			final Model< ? > qModel )
	{
		final List< PointMatch > relativePMs = new ArrayList<>( absolutePMs.size() );

		if ( absolutePMs.size() == 0 )
			return relativePMs;

		final int n = absolutePMs.get( 0 ).getP1().getL().length;

		for ( final PointMatch absPM : absolutePMs )
		{
			final double[] pLocal = new double[ n ];
			final double[] qLocal = new double[ n ];

			for (int d = 0; d < n; ++d )
			{
				pLocal[ d ] = absPM.getP1().getL()[ d ];
				qLocal[ d ] = absPM.getP2().getL()[ d ];
			}

			if ( pModel != null )
				pModel.applyInPlace( pLocal );

			if ( qModel != null )
				qModel.applyInPlace( qLocal );

			relativePMs.add( new PointMatch( new Point( pLocal ), new Point( qLocal ), absPM.getWeight() ) );
		}

		return relativePMs;
	}


	public static AffineModel2D createAffineModel( final RigidModel2D rigid )
	{
		final double[] array = new double[ 6 ];
		rigid.toArray( array );
		final AffineModel2D affine = new AffineModel2D();
		affine.set( array[ 0 ], array[ 1 ], array[ 2 ], array[ 3 ], array[ 4 ], array[ 5 ] );
		return affine;
	}

	//protected abstract void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException;

	// must be called after all Tilespecs are updated
	public static void completeStack( final String targetStack, final RunParameters runParams ) throws IOException
	{
		runParams.targetDataClient.setStackState( targetStack, StackState.COMPLETE );
	}

	public static < B extends Model< B > & Affine2D< B > > Pair< Tile< B >, AffineModel2D > buildTileFromSpec(
			final B instance,
			final int samplesPerDimension,
			final TileSpec tileSpec )
	{
        final AffineModel2D lastTransform = loadLastTransformFromSpec( tileSpec );
        final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        try {
            ScriptUtil.fit(instance, lastTransformCopy, sampleWidth, sampleHeight, samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(instance.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new ValuePair<>(
        		new Tile< B >( instance ), 
        		lastTransform.copy() );
	}


	public static < B extends Model< B > & Affine2D< B > > Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > buildTileFromSpec(
			final int samplesPerDimension,
			final ModelType regularizerModelType,
			final double startLambda,
			final TileSpec tileSpec )
	{
        final AffineModel2D lastTransform = loadLastTransformFromSpec( tileSpec );
        final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        final B regularizer = regularizerModelType.getInstance();

        try {
            ScriptUtil.fit(regularizer, lastTransformCopy, sampleWidth, sampleHeight, samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(regularizer.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new ValuePair<>(
        		new Tile<>(new InterpolatedAffineModel2D<>(
        				lastTransformCopy,
        				regularizer,
        				startLambda)), // note: lambda gets reset during optimization loops
        		lastTransform.copy() );
	}

	public static < M extends Model< M > & Affine2D< M > > Tile< M > buildTile(
			final AffineModel2D lastTransform,
			final M model,
			final int width,
			final int height,
			final int samplesPerDimension
			)
	{
        final double sampleWidth = (width - 1.0) / (samplesPerDimension - 1.0);
        final double sampleHeight = (height - 1.0) / (samplesPerDimension - 1.0);

        try
        {
            ScriptUtil.fit(model, lastTransform, sampleWidth, sampleHeight, samplesPerDimension);
        }
        catch (final Throwable t)
        {
            throw new IllegalArgumentException(model.getClass() + " model derivation failed, cause: " + t.getMessage(), t);
        }

        return new Tile<>(model);
	}

	public static TileSpec getTileSpec(
			final String stack,
			final RunParameters runParams,
			final String sectionId,
			final String tileId ) throws IOException {
		
		return getTileSpec( runParams.sectionIdToZMap, runParams.zToTileSpecsMap, runParams.renderDataClient, stack, sectionId, tileId );
	}

	public static TileSpec getTileSpec(
			final Map<String, ? extends List<Double>> sectionIdToZMap,
			final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap,
			final RenderDataClient renderDataClient,
			final String stack,
			final String sectionId,
			final String tileId ) throws IOException {

        TileSpec tileSpec = null;

        if (sectionIdToZMap.containsKey(sectionId)) {

            for (final Double z : sectionIdToZMap.get(sectionId)) {

                if ( !zToTileSpecsMap.containsKey(z)) {

//                    if (runParams.totalTileCount > 100000) {
//                        throw new IllegalArgumentException("More than 100000 tiles need to be loaded - please reduce z values");
//                    }

                    final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

                    // check for accidental use of rough aligned stack ...
                    resolvedTiles.getTileSpecs().forEach(ts -> {
                        if (ts.getLastTransform() instanceof ReferenceTransformSpec) {
                            throw new IllegalStateException(
                                    "last transform for tile " + ts.getTileId() +
                                    " is a reference transform which will break this fragile client, " +
                                    "make sure --stack is not a rough aligned stack ");
                        }
                    });

                    resolvedTiles.resolveTileSpecs();
                    zToTileSpecsMap.put(z, resolvedTiles);
                    //runParams.totalTileCount += resolvedTiles.getTileCount();
                }

                final ResolvedTileSpecCollection resolvedTileSpecCollection = zToTileSpecsMap.get(z);
                tileSpec = resolvedTileSpecCollection.getTileSpec(tileId);

                if (tileSpec != null) {
                    break;
                }
            }
            
        }

        return tileSpec;
    }

	public static RenderParameters getRenderParametersForTile( final String owner,
			final String project, final String stack, final String tileId,
			final double renderScale )
	{
		final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/" + owner + "/project/" + project + "/stack/" + stack + "/tile/";
		final String urlSuffix = "/render-parameters?scale=" + renderScale;
		// TODO: add &fillWithNoise=true ?
		// TODO: add &excludeMask=true ?
		final String url = baseTileUrl + tileId + urlSuffix;

		final RenderParameters renderParameters = RenderParameters.loadFromUrl( url );
		renderParameters.setDoFilter( false );
		renderParameters.initializeDerivedValues();

		renderParameters.validate();

		// remove mipmapPathBuilder so that we don't get exceptions when /nrs is
		// not mounted
		renderParameters.setMipmapPathBuilder( null );
		renderParameters.applyMipmapPathBuilderToTileSpecs();

		return renderParameters;
	}
	//
	// overwrites the area that was re-aligned or it preconcatenates
	//
	public static void saveTargetStackTiles(
			final String stack, // parameters.stack
			final String targetStack, // parameters.targetStack
			final RunParameters runParams,
			final Map< String, AffineModel2D > idToModel,
			final AffineModel2D relativeModel,
			final List< Double > zToSave,
			final TransformApplicationMethod applyMethod ) throws IOException
	{
		LOG.info( "saveTargetStackTiles: entry, saving tile specs in {} layers", zToSave.size() );

		for ( final Double z : zToSave )
		{
			final ResolvedTileSpecCollection resolvedTiles;

			if ( !runParams.zToTileSpecsMap.containsKey( z ) )
			{
				resolvedTiles = runParams.renderDataClient.getResolvedTiles( stack, z );
			}
			else
			{
				resolvedTiles = runParams.zToTileSpecsMap.get( z );
			}

			if ( idToModel != null || relativeModel != null )
			{
				for (final TileSpec tileSpec : resolvedTiles.getTileSpecs())
				{
					final String tileId = tileSpec.getTileId();
					final AffineModel2D model;
	
					if ( applyMethod.equals(  TransformApplicationMethod.REPLACE_LAST  ) )
						model = idToModel.get( tileId );
					else if ( applyMethod.equals( TransformApplicationMethod.PRE_CONCATENATE_LAST ))
						model = relativeModel;
					else
						throw new RuntimeException( "not supported: " + applyMethod );
	
					if ( model != null )
					{
						resolvedTiles.addTransformSpecToTile( tileId,
								getTransformSpec( model ),
								applyMethod );
					}
				}
			}

			if ( resolvedTiles.getTileCount() > 0 )
				runParams.targetDataClient.saveResolvedTiles( resolvedTiles, targetStack, null );
			else
				LOG.info( "skipping tile spec save since no specs are left to save" );
		}

		LOG.info( "saveTargetStackTiles: exit" );
	}

	private static LeafTransformSpec getTransformSpec( final AffineModel2D forModel )
	{
		final double[] m = new double[ 6 ];
		forModel.toArray( m );
		final String data = String.valueOf( m[ 0 ] ) + ' ' + m[ 1 ] + ' ' + m[ 2 ] + ' ' + m[ 3 ] + ' ' + m[ 4 ] + ' ' + m[ 5 ];
		return new LeafTransformSpec( mpicbg.trakem2.transform.AffineModel2D.class.getName(), data );
	}

	/*
	public static ImagePlus visualize(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final double[] scale ) throws NoninvertibleModelException
	{
		final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		final AffineModel2D scaleModel = new AffineModel2D();
		scaleModel.set( scale[ 0 ], 0, 0, scale[ 1 ], 0, 0 );

		final HashMap<String, AffineModel2D> idToRenderModels = new HashMap<>();

		// get bounding box
		for ( final String tileId : idToModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			min[ 2 ] = Math.min( min[ 2 ], tileSpec.getZ() / scale[ 2 ] );
			max[ 2 ] = Math.max( max[ 2 ], tileSpec.getZ() / scale[ 2 ] );

			final int w = tileSpec.getWidth();
			final int h = tileSpec.getHeight();

			final AffineModel2D model = idToModels.get( tileId ).copy();

			// scale the actual transform down to the scale level we want to render in
			model.preConcatenate( scaleModel );

			idToRenderModels.put( tileId, model );

			tmpMin[ 0 ] = 0;
			tmpMin[ 1 ] = 0;
			tmpMax[ 0 ] = w - 1;
			tmpMax[ 1 ] = h - 1;

			model.estimateBounds( tmpMin, tmpMax );

			min[ 0 ] = Math.min( min[ 0 ], Math.min( tmpMin[ 0 ], tmpMax[ 0 ] ) );
			max[ 0 ] = Math.max( max[ 0 ], Math.max( tmpMin[ 0 ], tmpMax[ 0 ] ) );

			min[ 1 ] = Math.min( min[ 1 ], Math.min( tmpMin[ 1 ], tmpMax[ 1 ] ) );
			max[ 1 ] = Math.max( max[ 1 ], Math.max( tmpMin[ 1 ], tmpMax[ 1 ] ) );
		}

		System.out.println( "x: " + min[ 0 ] + " >>> " + max[ 0 ] );
		System.out.println( "y: " + min[ 1 ] + " >>> " + max[ 1 ] );
		System.out.println( "z: " + min[ 2 ] + " >>> " + max[ 2 ] );

		final long[] minI = new long[ 3 ];
		final long[] maxI = new long[ 3 ];
		final long[] dimI = new long[ 3 ];

		for ( int d = 0; d < minI.length; ++d )
		{
			minI[ d ] = Math.round( Math.floor( min[ d ] ) );
			maxI[ d ] = Math.round( Math.ceil( max[ d ] ) );
			dimI[ d ] = maxI[ d ] - minI[ d ] + 1;
		}

		System.out.println( "BB x: " + minI[ 0 ] + " >>> " + maxI[ 0 ] + ", d=" + dimI[ 0 ] );
		System.out.println( "BB y: " + minI[ 1 ] + " >>> " + maxI[ 1 ] + ", d=" + dimI[ 1 ]);
		System.out.println( "BB z: " + minI[ 2 ] + " >>> " + maxI[ 2 ] + ", d=" + dimI[ 2 ]);

		// init image
		final ImagePlusImg< UnsignedByteType, ? > stack = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType()).create( dimI );
		final RandomAccessibleInterval< UnsignedByteType > img = Views.translate( stack, minI );

		final AffineModel2D invScaleModel = new AffineModel2D();
		invScaleModel.set( 1.0/scale[ 0 ], 0, 0, 1.0/scale[ 1 ], 0, 0 );

		// build the lookup z to tilespec
		final HashMap<Integer, ArrayList< Pair<String,MinimalTileSpec> > > zToTileSpec = new HashMap<>(); 

		for ( final String tileId : idToRenderModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			final int z = (int)Math.round( tileSpec.getZ() );
			zToTileSpec.putIfAbsent(z, new ArrayList<>());
			zToTileSpec.get( z ).add( new ValuePair<>( tileId, tileSpec ) );
		}

		
		// render the images
		int i = 0;
		for ( final String tileId : idToRenderModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			final long z = Math.round( tileSpec.getZ() );
			final AffineModel2D model = idToRenderModels.get( tileId );

			// scale the transform so it takes into account that the input images are scaled
			model.concatenate( invScaleModel );

			final ImageProcessorWithMasks imp = getImage( tileSpec, scale );
			RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.ip) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.mask) ) ), new NearestNeighborInterpolatorFactory() );
			
			// draw
			final IterableInterval< UnsignedByteType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< UnsignedByteType > c = slice.cursor();
			
			AffineTransform2D affine = new AffineTransform2D();
			double[] array = new double[6];
			model.toArray( array );
			affine.set( array[0], array[2], array[4], array[1], array[3], array[5] );
			final Cursor< FloatType > cSrc = Views.interval( RealViews.affine( interpolant, affine ), img ).cursor();
			final Cursor< UnsignedByteType > cMask = Views.interval( RealViews.affine( interpolantMask, affine ), img ).cursor();
			
			while ( c.hasNext() )
			{
				c.fwd();
				cMask.fwd();
				cSrc.fwd();
				if (cMask.get().get() == 255) {
					FloatType srcType = cSrc.get();
					float value = srcType.get();
					if (value >= 0) {
						UnsignedByteType type = c.get();
						final float currentValue = type.get();
						if ( currentValue > 0 )
							type.setReal( ( value + currentValue ) / 2 );
						else
							type.setReal( value );
					}
				}
			}

			IJ.showProgress( ++i, idToRenderModels.keySet().size() - 1 );
		}


		//final ImagePlus imp = ImageJFunctions.wrap( img, "stack", null );
		final ImagePlus imp = stack.getImagePlus();

		Calibration cal = new Calibration();
		cal.xOrigin = -minI[ 0 ];
		cal.yOrigin = -minI[ 1 ];
		cal.zOrigin = -minI[ 2 ];
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp.setCalibration( cal );
		imp.setDimensions( 1, (int)dimI[ 2 ], 1 );
		imp.setDisplayRange( 0, 255 );
		imp.show();

		return imp;
	}
	*/
	public static ImagePlus render( final HashMap<String, AffineModel2D> idToModels, final HashMap<String, MinimalTileSpec> idToTileSpec, final double scale ) throws NoninvertibleModelException
	{
		return render(idToModels, idToTileSpec, scale, Integer.MIN_VALUE, Integer.MAX_VALUE );
	}

	public static ImagePlus render( final HashMap<String, AffineModel2D> idToModelsIn, final HashMap<String, MinimalTileSpec> idToTileSpecIn, final double scale, final int minZ, final int maxZ ) throws NoninvertibleModelException
	{
		final HashMap<String, AffineModel2D> idToModels = new HashMap<String, AffineModel2D>();
		final HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<String, MinimalTileSpec>();

		int count = 0;

		LOG.info( "MinZ=" + minZ + " maxZ=" + maxZ );

		for ( final String tileId : idToModelsIn.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpecIn.get( tileId );
			final AffineModel2D model = idToModelsIn.get( tileId );
			
			if ( tileSpec.getZ() >= minZ && tileSpec.getZ() <= maxZ )
			{
				idToModels.put( tileId, model );
				idToTileSpec.put( tileId, tileSpec );
				++count;
			}
		}

		if ( count == 0 )
		{
			LOG.info( "No tiles for the range available." );
			return null;
		}
		else
		{
			LOG.info( "Rendering " + count + " tiles." );			
		}

		final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		final AffineModel2D scaleModel = new AffineModel2D();
		scaleModel.set( scale, 0, 0, scale, 0, 0 );

		final HashMap<String, AffineModel2D> idToRenderModels = new HashMap<>();

		// get bounding box
		for ( final String tileId : idToModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			min[ 2 ] = Math.min( min[ 2 ], tileSpec.getZ() );
			max[ 2 ] = Math.max( max[ 2 ], tileSpec.getZ() );

			final int w = tileSpec.getWidth();
			final int h = tileSpec.getHeight();

			final AffineModel2D model = idToModels.get( tileId ).copy();

			// scale the actual transform down to the scale level we want to render in
			model.preConcatenate( scaleModel );

			idToRenderModels.put( tileId, model );

			tmpMin[ 0 ] = 0;
			tmpMin[ 1 ] = 0;
			tmpMax[ 0 ] = w - 1;
			tmpMax[ 1 ] = h - 1;

			model.estimateBounds( tmpMin, tmpMax );

			min[ 0 ] = Math.min( min[ 0 ], Math.min( tmpMin[ 0 ], tmpMax[ 0 ] ) );
			max[ 0 ] = Math.max( max[ 0 ], Math.max( tmpMin[ 0 ], tmpMax[ 0 ] ) );

			min[ 1 ] = Math.min( min[ 1 ], Math.min( tmpMin[ 1 ], tmpMax[ 1 ] ) );
			max[ 1 ] = Math.max( max[ 1 ], Math.max( tmpMin[ 1 ], tmpMax[ 1 ] ) );
		}

		System.out.println( "x: " + min[ 0 ] + " >>> " + max[ 0 ] );
		System.out.println( "y: " + min[ 1 ] + " >>> " + max[ 1 ] );
		System.out.println( "z: " + min[ 2 ] + " >>> " + max[ 2 ] );

		final long[] minI = new long[ 3 ];
		final long[] maxI = new long[ 3 ];
		final long[] dimI = new long[ 3 ];

		for ( int d = 0; d < minI.length; ++d )
		{
			minI[ d ] = Math.round( Math.floor( min[ d ] ) );
			maxI[ d ] = Math.round( Math.ceil( max[ d ] ) );
			dimI[ d ] = maxI[ d ] - minI[ d ] + 1;
		}

		System.out.println( "BB x: " + minI[ 0 ] + " >>> " + maxI[ 0 ] + ", d=" + dimI[ 0 ] );
		System.out.println( "BB y: " + minI[ 1 ] + " >>> " + maxI[ 1 ] + ", d=" + dimI[ 1 ]);
		System.out.println( "BB z: " + minI[ 2 ] + " >>> " + maxI[ 2 ] + ", d=" + dimI[ 2 ]);

		// init image
		final ImagePlusImg< UnsignedByteType, ? > stack = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType()).create( dimI );
		final RandomAccessibleInterval< UnsignedByteType > img = Views.translate( stack, minI );

		final AffineModel2D invScaleModel = new AffineModel2D();
		invScaleModel.set( 1.0/scale, 0, 0, 1.0/scale, 0, 0 );

		// render the images
		int i = 0;
		for ( final String tileId : idToRenderModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			final long z = Math.round( tileSpec.getZ() );
			final AffineModel2D model = idToRenderModels.get( tileId );

			// scale the transform so it takes into account that the input images are scaled
			model.concatenate( invScaleModel );

			final ImageProcessorWithMasks imp = getImage( tileSpec, scale );
			RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.ip) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.mask) ) ), new NearestNeighborInterpolatorFactory() );
			
			// draw
			final IterableInterval< UnsignedByteType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< UnsignedByteType > c = slice.cursor();
			
			AffineTransform2D affine = new AffineTransform2D();
			double[] array = new double[6];
			model.toArray( array );
			affine.set( array[0], array[2], array[4], array[1], array[3], array[5] );
			final Cursor< FloatType > cSrc = Views.interval( RealViews.affine( interpolant, affine ), img ).cursor();
			final Cursor< UnsignedByteType > cMask = Views.interval( RealViews.affine( interpolantMask, affine ), img ).cursor();
			
			while ( c.hasNext() )
			{
				c.fwd();
				cMask.fwd();
				cSrc.fwd();
				if (cMask.get().get() == 255) {
					FloatType srcType = cSrc.get();
					float value = srcType.get();
					if (value >= 0) {
						UnsignedByteType type = c.get();
						final float currentValue = type.get();
						if ( currentValue > 0 )
							type.setReal( ( value + currentValue ) / 2 );
						else
							type.setReal( value );
					}
				}
			}

			IJ.showProgress( ++i, idToRenderModels.keySet().size() - 1 );
		}


		//final ImagePlus imp = ImageJFunctions.wrap( img, "stack", null );
		final ImagePlus imp = stack.getImagePlus();

		Calibration cal = new Calibration();
		cal.xOrigin = -minI[ 0 ];
		cal.yOrigin = -minI[ 1 ];
		cal.zOrigin = -minI[ 2 ];
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp.setCalibration( cal );
		imp.setDimensions( 1, (int)dimI[ 2 ], 1 );
		imp.setDisplayRange( 0, 255 );
		imp.show();

		return imp;
	}

	protected static FloatProcessor getFullResImage( final MinimalTileSpec tileSpec )
	{
		final String fileName = tileSpec.getFileName();

		if ( new File( fileName ).exists() )
			return new ImagePlus( fileName ).getProcessor().convertToFloatProcessor();
		else
		{
			System.out.println( "WARNING: File path '" + new File( fileName ).getAbsolutePath() + "' does not exist. Shares mounted?" );
			return null;
		}

	}

	protected static ImageProcessor getFullResMask( final MinimalTileSpec tileSpec )
	{
		final String fileNameMask = tileSpec.getMaskFileName();

		if ( new File( fileNameMask ).exists() )
			return new ImagePlus( fileNameMask ).getProcessor();
		else
		{
			System.out.println( "WARNING: File path '" + new File( fileNameMask ).getAbsolutePath() + "' does not exist. Shares mounted?" );
			return null;
		}
	}

	protected static ImageProcessorWithMasks getImage( final MinimalTileSpec tileSpec, final double scale )
	{
		// old code:
		final File imageFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".image.tif" );
		final File maskFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".mask.tif" );

		final ImageProcessorWithMasks imp;

		if ( imageFile.exists() && maskFile.exists() )
		{
			//System.out.println( "Loading: " + imageFile );
			//System.out.println( "Loading: " + maskFile );

			final ImagePlus image = new ImagePlus( imageFile.getAbsolutePath() );
			final ImagePlus mask = new ImagePlus( maskFile.getAbsolutePath() );

			imp = new ImageProcessorWithMasks( image.getProcessor(), mask.getProcessor(), null );
		}
		else
		{
			// this gives a transformed image, but we need a raw image
			/*
			// Load the image, this is not efficient!
			final RenderParameters params = getRenderParametersForTile(
					parameters.renderWeb.owner,
					parameters.renderWeb.project,
					parameters.stack,
					tileSpec.getTileId(),
					scale );
	
			imp = Renderer.renderImageProcessorWithMasks(params, ImageProcessorCache.DISABLED_CACHE);
			*/
			
			// this gives a raw image
			FloatProcessor imageFP = getFullResImage( tileSpec );
			ImageProcessor maskIP = getFullResMask( tileSpec );

			imageFP = (FloatProcessor)imageFP.resize( (int)Math.round( imageFP.getWidth() * scale ), (int)Math.round( imageFP.getHeight() * scale ), true );
			maskIP = maskIP.resize( (int)Math.round( maskIP.getWidth() * scale ), (int)Math.round( maskIP.getHeight() * scale ), true );

			// hack to get a not transformed image:
			imp = new ImageProcessorWithMasks( imageFP, maskIP, null );

			// write temp if doesn't exist
			if ( !imageFile.exists() || !maskFile.exists() )
			{
				System.out.println( "Saving: " + imageFile );
				System.out.println( "Saving: " + maskFile );
	
				final ImagePlus image = new ImagePlus( "image", imp.ip );
				final ImagePlus mask = new ImagePlus( "mask", imp.mask );
	
				new FileSaver( image ).saveAsTiff( imageFile.getAbsolutePath() );
				new FileSaver( mask ).saveAsTiff( maskFile.getAbsolutePath() );
			}
		}

		return imp;
	}

	public static AffineModel2D loadLastTransformFromSpec( final TileSpec tileSpec )
	{
		// TODO: make sure there is only one transform
        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();

        if ( transformList.getList( null ).size() != 1 )
        	throw new RuntimeException( "size " + transformList.getList( null ).size() );
        final AffineModel2D lastTransform = (AffineModel2D)
                transformList.get(transformList.getList(null).size() - 1);
        return lastTransform;
	}

	private static final Logger LOG = LoggerFactory.getLogger(SolveTools.class);
}
