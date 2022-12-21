package org.janelia.render.client.solve;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

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
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Tile;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
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

public abstract class PartialSolve< B extends Model< B > & Affine2D< B > >
{
	final protected Parameters parameters;
	protected final RenderDataClient renderDataClient;
	protected final RenderDataClient matchDataClient;
	protected final RenderDataClient targetDataClient;
	
	protected final List<String> pGroupList;
	protected final Map<String, List<Double>> sectionIdToZMap;
	protected final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;
	protected double minZ, maxZ; // min will be fixed, max will be grouped
	protected int totalTileCount;

	protected abstract void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException;

	// must be called after all Tilespecs are updated
	protected void completeStack() throws IOException
	{
		if ( parameters.completeTargetStack )
			targetDataClient.setStackState( parameters.targetStack, StackState.COMPLETE );
	}

	//
	// overwrites the area that was re-aligned or it preconcatenates
	//
	protected void saveTargetStackTiles(
			final Map< String, AffineModel2D > idToModel,
			final AffineModel2D relativeModel,
			final List< Double > zToSave,
			final TransformApplicationMethod applyMethod ) throws IOException
	{
		LOG.info( "saveTargetStackTiles: entry, saving tile specs in {} layers", zToSave.size() );

		for ( final Double z : zToSave )
		{
			final ResolvedTileSpecCollection resolvedTiles;

			if ( !zToTileSpecsMap.containsKey( z ) )
			{
				resolvedTiles = renderDataClient.getResolvedTiles( parameters.stack, z );
			}
			else
			{
				resolvedTiles = zToTileSpecsMap.get( z );
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
				targetDataClient.saveResolvedTiles( resolvedTiles, parameters.targetStack, null );
			else
				LOG.info( "skipping tile spec save since no specs are left to save" );
		}

		LOG.info( "saveTargetStackTiles: exit" );
	}

	private LeafTransformSpec getTransformSpec( final AffineModel2D forModel )
	{
		final double[] m = new double[ 6 ];
		forModel.toArray( m );
		final String data = String.valueOf( m[ 0 ] ) + ' ' + m[ 1 ] + ' ' + m[ 2 ] + ' ' + m[ 3 ] + ' ' + m[ 4 ] + ' ' + m[ 5 ];
		return new LeafTransformSpec( mpicbg.trakem2.transform.AffineModel2D.class.getName(), data );
	}

	public ImagePlus render( final HashMap<String, AffineModel2D> idToModels,
							 final HashMap<String, TileSpec> idToTileSpec,
							 final double scale,
							 final String visualizationDirectory ) throws NoninvertibleModelException
	{
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
			final TileSpec tileSpec = idToTileSpec.get( tileId );
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
		final RandomAccessibleInterval< FloatType > img = Views.translate( ArrayImgs.floats( dimI ), minI );

		final AffineModel2D invScaleModel = new AffineModel2D();
		invScaleModel.set( 1.0/scale, 0, 0, 1.0/scale, 0, 0 );

		// render the images
		int i = 0;
		for ( final String tileId : idToRenderModels.keySet() )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final long z = Math.round( tileSpec.getZ() );
			final AffineModel2D model = idToRenderModels.get( tileId );

			// scale the transform so it takes into account that the input images are scaled
			model.concatenate( invScaleModel );

			final ImageProcessorWithMasks imp = getImage( tileSpec, scale, visualizationDirectory );
			RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.ip) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.mask) ) ), new NearestNeighborInterpolatorFactory() );
			
			// draw
			final IterableInterval< FloatType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< FloatType > c = slice.cursor();
			
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
						FloatType type = c.get();
						final float currentValue = type.get();
						if ( currentValue > 0 )
							type.set( ( value + currentValue ) / 2 );
						else
							type.set( value );
					}
				}
			}

			IJ.showProgress( ++i, idToRenderModels.keySet().size() - 1 );
		}


		final ImagePlus imp = ImageJFunctions.wrap( img, "stack", null );

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

	public PartialSolve(final Parameters parameters) throws IOException
	{
		parameters.initDefaultValues();

		this.parameters = parameters;

		this.renderDataClient = parameters.renderWeb.getDataClient();
		this.matchDataClient = new RenderDataClient(
				parameters.renderWeb.baseDataUrl,
				parameters.matchOwner,
				parameters.matchCollection);

		this.sectionIdToZMap = new TreeMap<>();
		this.zToTileSpecsMap = new HashMap<>();
		this.totalTileCount = 0;

		if (parameters.targetStack == null)
		{
			this.targetDataClient = null;
		}
		else
		{
			this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl, parameters.targetOwner, parameters.targetProject);

			final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
			targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
		}

		final ZFilter zFilter = new ZFilter(parameters.minZ,parameters.maxZ,null);
		final List<SectionData> allSectionDataList = renderDataClient.getStackSectionData(parameters.stack, null, null );

		this.pGroupList = new ArrayList<>(allSectionDataList.size());
		this.pGroupList.addAll(
				allSectionDataList.stream()
						.filter(sectionData -> zFilter.accept(sectionData.getZ()))
						.map(SectionData::getSectionId)
						.distinct()
						.sorted()
						.collect(Collectors.toList()));
		
		if (this.pGroupList.size() == 0)
			throw new IllegalArgumentException("stack " + parameters.stack + " does not contain any sections with the specified z values");

		Double minZForRun = parameters.minZ;
		Double maxZForRun = parameters.maxZ;

		if ((minZForRun == null) || (maxZForRun == null))
		{
			final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
			final StackStats stackStats = stackMetaData.getStats();
			if (stackStats != null)
			{
				final Bounds stackBounds = stackStats.getStackBounds();
				if (stackBounds != null)
				{
					if (minZForRun == null)
						minZForRun = stackBounds.getMinZ();

					if (maxZForRun == null)
						maxZForRun = stackBounds.getMaxZ();
				}
			}

			if ( (minZForRun == null) || (maxZForRun == null) )
				throw new IllegalArgumentException( "Failed to derive min and/or max z values for stack " + parameters.stack + ".  Stack may need to be completed.");
		}

		final Double minZ = minZForRun;
		final Double maxZ = maxZForRun;

		this.minZ = minZForRun;
		this.maxZ = maxZForRun;

		allSectionDataList.forEach(sd ->
		{
			final Double z = sd.getZ();
			if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0))
			{
				final List<Double> zListForSection = sectionIdToZMap.computeIfAbsent(
						sd.getSectionId(), zList -> new ArrayList<>());

				zListForSection.add(sd.getZ());
			}
		});
	}

	protected FloatProcessor getFullResImage( final TileSpec tileSpec )
	{
		final String fileName = tileSpec.getFirstMipmapEntry().getValue().getImageFilePath();

		if ( new File( fileName ).exists() )
			return new ImagePlus( fileName ).getProcessor().convertToFloatProcessor();
		else
			return null;

	}

	protected ImageProcessor getFullResMask( final TileSpec tileSpec )
	{
		final String fileNameMask = tileSpec.getFirstMipmapEntry().getValue().getMaskFilePath();

		if ( new File( fileNameMask ).exists() )
			return new ImagePlus( fileNameMask ).getProcessor();
		else
			return null;
	}

	protected ImageProcessorWithMasks getImage( final TileSpec tileSpec,
												final double scale,
												final String visualizationDirectory  )
	{
		// old code:
		final File imageFile = new File( visualizationDirectory, tileSpec.getTileId() + "_" + scale + ".image.tif" );
		final File maskFile = new File( visualizationDirectory, tileSpec.getTileId() + "_" + scale + ".mask.tif" );

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

	protected AffineModel2D loadLastTransformFromSpec( final TileSpec tileSpec )
	{
		// TODO: make sure there is only one transform
        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();

        if ( transformList.getList( null ).size() != 1 )
        	throw new RuntimeException( "size " + transformList.getList( null ).size() );
        final AffineModel2D lastTransform = (AffineModel2D)
                transformList.get(transformList.getList(null).size() - 1);
        return lastTransform;
	}

	protected Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > buildTileFromSpec( final TileSpec tileSpec )
	{
        final AffineModel2D lastTransform = loadLastTransformFromSpec( tileSpec );
        final AffineModel2D lastTransformCopy = lastTransform.copy();

        final double sampleWidth = (tileSpec.getWidth() - 1.0) / (parameters.samplesPerDimension - 1.0);
        final double sampleHeight = (tileSpec.getHeight() - 1.0) / (parameters.samplesPerDimension - 1.0);

        final B regularizer = parameters.regularizerModelType.getInstance();

        try {
            ScriptUtil.fit(regularizer, lastTransformCopy, sampleWidth, sampleHeight, parameters.samplesPerDimension);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(regularizer.getClass() + " model derivation failed for tile '" +
                                               tileSpec.getTileId() + "', cause: " + t.getMessage(),
                                               t);
        }

        return new ValuePair<>(
        		new Tile<>(new InterpolatedAffineModel2D<>(
        				lastTransformCopy,
        				regularizer,
        				parameters.startLambda)), // note: lambda gets reset during optimization loops
        		lastTransform.copy() );
	}

	protected TileSpec getTileSpec(final String sectionId,
                                 final String tileId)
            throws IOException {

        TileSpec tileSpec = null;

        if (sectionIdToZMap.containsKey(sectionId)) {

            for (final Double z : sectionIdToZMap.get(sectionId)) {

                if (! zToTileSpecsMap.containsKey(z)) {

                    if (totalTileCount > 100000) {
                        throw new IllegalArgumentException("More than 100000 tiles need to be loaded - please reduce z values");
                    }

                    final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);

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
                    totalTileCount += resolvedTiles.getTileCount();
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

	static RenderParameters getRenderParametersForTile( final String owner,
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

    public static class Parameters extends CommandLineParameters {

		private static final long serialVersionUID = 6845718387096692785L;

		@ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--minZ",
                description = "Minimum (split) Z value for layers to be processed")
        public Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum (split) Z value for layers to be processed")
        public Double maxZ;

        @Parameter(
                names = "--matchOwner",
                description = "Owner of match collection for tiles (default is owner)"
        )
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Name of match collection for tiles",
                required = true
        )
        public String matchCollection;

        @Parameter(
                names = "--regularizerModelType",
                description = "Type of model for regularizer",
                required = true
        )
        public ModelType regularizerModelType;

        @Parameter(
                names = "--samplesPerDimension",
                description = "Samples per dimension"
        )
        public Integer samplesPerDimension = 2;

        @Parameter(
                names = "--maxAllowedError",
                description = "Max allowed error"
        )
        public Double maxAllowedError = 200.0;

        @Parameter(
                names = "--maxIterations",
                description = "Max iterations"
        )
        public Integer maxIterations = 10000;

        @Parameter(
                names = "--maxPlateauWidth",
                description = "Max allowed error"
        )
        public Integer maxPlateauWidth = 800;

        @Parameter(
                names = "--startLambda",
                description = "Starting lambda for optimizer.  " +
                              "Optimizer loops through lambdas 1.0, 0.5, 0.1. 0.01.  " +
                              "If you know your starting alignment is good, " +
                              "set this to one of the smaller values to improve performance."
        )
        public Double startLambda = 1.0;

        @Parameter(
                names = "--optimizerLambdas",
                description = "Explicit optimizer lambda values.",
                variableArity = true
        )
        public List<Double> optimizerLambdas;

        @Parameter(
                names = "--targetOwner",
                description = "Owner name for aligned result stack (default is same as owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Project name for aligned result stack (default is same as project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name for aligned result stack (if omitted, aligned models are simply logged)")
        public String targetStack;

        @Parameter(
                names = "--mergedZ",
                description = "Z value for all aligned tiles (if omitted, original split z values are kept)"
        )
        public Double mergedZ;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after processing",
                arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(names = "--threads", description = "Number of threads to be used")
        public int numberOfThreads = 1;

		// how many layers on the top and bottom we use as overlap to compute the rigid models that "blend" the re-solved stack back in
		@Parameter(
				names = "--overlapTop",
				description = "Number of layers above (z > maxZ) to use as overlap " +
							  "to compute the rigid models that 'blend' the re-solved stack back in",
				required = true)
		public int overlapTop;

		@Parameter(
				names = "--overlapBottom",
				description = "Number of layers below (z < minZ) to use as overlap " +
							  "to compute the rigid models that 'blend' the re-solved stack back in",
				required = true)
		public int overlapBottom;

		@Parameter(
				names = "--visualizeResults",
				description = "Render and display alignment results (in ImageJ)",
				arity = 0)
		public boolean visualizeResults = false;

		@Parameter(
				names = "--visualizationDirectory",
				description = "Directory for caching visualization images locally (to speed up visualization process)"
		)
		public String visualizationDirectory = "tmp";

		public Parameters() {
        }

        void initDefaultValues() {

            if (this.matchOwner == null) {
                this.matchOwner = renderWeb.owner;
            }

            if (this.targetOwner == null) {
                this.targetOwner = renderWeb.owner;
            }

            if (this.targetProject == null) {
                this.targetProject = renderWeb.project;
            }
        }

    }

	private static final Logger LOG = LoggerFactory.getLogger(PartialSolve.class);
}
