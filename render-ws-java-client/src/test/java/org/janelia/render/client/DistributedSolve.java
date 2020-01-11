package org.janelia.render.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ScriptUtil;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class DistributedSolve< B extends Model< B > & Affine2D< B > >
{
	final Parameters parameters;
	private final RenderDataClient renderDataClient;
	private final RenderDataClient matchDataClient;
	private final RenderDataClient targetDataClient;
	
	private final List<String> pGroupList;
	private final Map<String, List<Double>> sectionIdToZMap;
	private final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap;
	private int totalTileCount;

	private void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		LOG.info("run: entry");

		final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();
		final HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();
		final HashMap<String, TileSpec> idToTileSpec = new HashMap<>();

		for (final String pGroupId : pGroupList)
		{
			LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = getTileSpec(pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = getTileSpec(qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, parameters.stack);
					continue;
				}

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !idToTileMap.containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = buildTileFromSpec(pTileSpec);
					p = pairP.getA();
					idToTileMap.put( pId, p );
					idToPreviousModel.put( pId, pairP.getB() );
					idToTileSpec.put( pId, pTileSpec );
				}
				else
				{
					p = idToTileMap.get( pId );
				}

				if ( !idToTileMap.containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = buildTileFromSpec(qTileSpec);
					q = pairQ.getA();
					idToTileMap.put( qId, q );
					idToPreviousModel.put( qId, pairQ.getB() );
					idToTileSpec.put( qId, qTileSpec );
				}
				else
				{
					q = idToTileMap.get( qId );
				}

				//final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p = idToTileMap.computeIfAbsent(pId, pTile -> buildTileFromSpec(pTileSpec));
				//final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> q = idToTileMap.computeIfAbsent(qId, qTile -> buildTileFromSpec(qTileSpec));

				p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
			}
		}

		// visualize old result
		render( idToPreviousModel, idToTileSpec, 0.15 );

		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles(idToTileMap.values());
		
		LOG.info("run: optimizing {} tiles", idToTileMap.size());
		
		final List<Double> lambdaValues;
		if (parameters.optimizerLambdas == null) {
			lambdaValues = Stream.of(1.0, 0.5, 0.1, 0.01)
					.filter(lambda -> lambda <= parameters.startLambda)
					.collect(Collectors.toList());
		} else {
			lambdaValues = parameters.optimizerLambdas.stream()
					.sorted(Comparator.reverseOrder())
					.collect(Collectors.toList());
		}
		
		for (final double lambda : lambdaValues)
		{
			for (final Tile tile : idToTileMap.values()) {
				((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
			}
		
			// tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);
		
			final ErrorStatistic observer = new ErrorStatistic(parameters.maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					parameters.maxAllowedError,
					parameters.maxIterations,
					parameters.maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					parameters.numberOfThreads);
		}

		if (parameters.targetStack == null)
		{
			for (final String tileId : idToTileMap.keySet())
			{
				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
				final InterpolatedAffineModel2D model = tile.getModel();
				LOG.info("tile {} model is {}", tileId, model.createAffineModel2D());
			}
		}
		else
		{
			//saveTargetStackTiles(idToTileMap);
		}
		
		LOG.info("run: exit");

	}

	public void render( final HashMap<String, AffineModel2D> models, final HashMap<String, TileSpec> idToTileSpec, final double scale ) throws NoninvertibleModelException
	{
		final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		final AffineModel2D scaleModel = new AffineModel2D();
		scaleModel.set( scale, 0, 0, scale, 0, 0 );

		// get bounding box
		for ( final String tileId : models.keySet() )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			min[ 2 ] = Math.min( min[ 2 ], tileSpec.getZ() );
			max[ 2 ] = Math.max( max[ 2 ], tileSpec.getZ() );

			final int w = tileSpec.getWidth();
			final int h = tileSpec.getHeight();

			// concatenate the scaling
			final AffineModel2D model = models.get( tileId );
			model.preConcatenate( scaleModel );

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

		new ImageJ();

		// render the images
		int i = 0;
		for ( final String tileId : models.keySet() )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final long z = Math.round( tileSpec.getZ() );
			final AffineModel2D model = models.get( tileId ).copy();
			model.concatenate( invScaleModel );

			final ImageProcessorWithMasks imp = getImage( tileSpec, scale );
			final int w = imp.getWidth();
			final int h = imp.getHeight();

			// draw
			final IterableInterval< FloatType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< FloatType > c = slice.localizingCursor();
			final double[] tmp = new double[ 2 ];

			while ( c.hasNext() )
			{
				final FloatType type = c.next();
				c.localize( tmp );
				model.applyInverseInPlace( tmp );

				final int x = (int)Math.round( tmp[ 0 ] );
				final int y = (int)Math.round( tmp[ 1 ] );

				if ( x >= 0 && x < w && y >= 0 && y < h && imp.mask.getf( x, y ) >= 255 )
					type.set( imp.ip.getf( x, y ) );
			}

			IJ.showProgress( ++i, models.keySet().size() - 1 );
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

		SimpleMultiThreading.threadHaltUnClean();
	}

	public DistributedSolve(final Parameters parameters) throws IOException
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

	private ImageProcessorWithMasks getImage( final TileSpec tileSpec, final double scale )
	{
		final File imageFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".image.tif" );
		final File maskFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".mask.tif" );

		final ImageProcessorWithMasks imp;

		if ( imageFile.exists() && maskFile.exists() )
		{
			System.out.println( "Loading: " + imageFile );
			System.out.println( "Loading: " + maskFile );

			final ImagePlus image = new ImagePlus( imageFile.getAbsolutePath() );
			final ImagePlus mask = new ImagePlus( maskFile.getAbsolutePath() );

			imp = new ImageProcessorWithMasks( image.getProcessor(), mask.getProcessor(), null );
		}
		else
		{
			// Load the image, this is not efficient!
			final RenderParameters params = getRenderParametersForTile(
					parameters.renderWeb.owner,
					parameters.renderWeb.project,
					parameters.stack,
					tileSpec.getTileId(),
					scale );
	
			imp = Renderer.renderImageProcessorWithMasks(params, ImageProcessorCache.DISABLED_CACHE);
	
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

	private Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > buildTileFromSpec( final TileSpec tileSpec )
	{

		// TODO: make sure there is only one transform
        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        final AffineModel2D lastTransform = (AffineModel2D)
                transformList.get(transformList.getList(null).size() - 1);
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

    private TileSpec getTileSpec(final String sectionId,
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

	public static void main( String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_19m",
                            "--project", "Sec10",

                            "--stack", "v2_patch_msolve_fine",
                            //"--targetStack", "null",
                            "--regularizerModelType", "TRANSLATION",
                            "--optimizerLambdas", "1.0",
                            "--minZ", "20500",
                            "--maxZ", "20600",

                            "--threads", "4",
                            "--maxIterations", "200",
                            "--completeTargetStack",
                            "--matchCollection", "Sec10_patch"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final DistributedSolve client = new DistributedSolve(parameters);

                client.run();
            }
        };
        clientRunner.run();
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
        public Integer maxIterations = 2000;

        @Parameter(
                names = "--maxPlateauWidth",
                description = "Max allowed error"
        )
        public Integer maxPlateauWidth = 200;

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

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolve.class);
}
