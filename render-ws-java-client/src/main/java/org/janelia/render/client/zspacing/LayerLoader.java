package org.janelia.render.client.zspacing;

import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.util.ImageProcessorCache;

/**
 * Interface (and several implementations) for loading aligned layer pixels for z position correction.
 *
 * @author Eric Trautman
 */
public interface LayerLoader {

    /**
     * @return total number of slices to load.
     */
    int getNumberOfLayers();

    /**
     * @return image processor for the specified slice index.
     */
    FloatProcessor getProcessor(final int layerIndex);

    /**
     * @return image mask and processor for the specified slice index.
     */
    Pair<FloatProcessor,FloatProcessor> getMaskAndProcessor(final int layerIndex);

    /**
     * Simple LRU cache of loaded processors for slices.
     */
    class SimpleLeastRecentlyUsedLayerCache
            implements LayerLoader {

        private final LayerLoader loader;
        private final int maxNumberOfLayersToCache;

        private final LinkedHashMap<Integer, FloatProcessor> indexToLayerProcessor;
        private final LinkedHashMap<Integer, Pair<FloatProcessor, FloatProcessor>> indexToLayerProcessorImageAndMask;

        public SimpleLeastRecentlyUsedLayerCache(final LayerLoader loader,
                                                 final int maxNumberOfLayersToCache) {
            this.loader = loader;
            this.maxNumberOfLayersToCache = maxNumberOfLayersToCache;
            this.indexToLayerProcessor = new LinkedHashMap<>();
            this.indexToLayerProcessorImageAndMask = new LinkedHashMap<>();
        }

        @Override
        public int getNumberOfLayers() {
            return loader.getNumberOfLayers();
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {
            return threadSafeGet(layerIndex);
        }

		@Override
		public Pair<FloatProcessor, FloatProcessor> getMaskAndProcessor(int layerIndex) {
			return threadSafeGetMaskAndProcessor(layerIndex);
		}

        private synchronized FloatProcessor threadSafeGet(final int layerIndex) {

            FloatProcessor processor = indexToLayerProcessor.remove(layerIndex);

            if (processor == null) {

                if (indexToLayerProcessor.size() >= maxNumberOfLayersToCache) {
                    final Integer leastRecentlyUsedIndex = indexToLayerProcessor.keySet()
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("cache should have at least one element"));
                    indexToLayerProcessor.remove(leastRecentlyUsedIndex);
                }

                processor = loader.getProcessor(layerIndex);
            }

            // reorder linked hash map so that most recently used is last
            indexToLayerProcessor.put(layerIndex, processor);

            return processor;
        }

        private synchronized Pair<FloatProcessor, FloatProcessor> threadSafeGetMaskAndProcessor(final int layerIndex) {

        	Pair<FloatProcessor, FloatProcessor> processors = indexToLayerProcessorImageAndMask.remove(layerIndex);

            if (processors == null) {

                if (indexToLayerProcessorImageAndMask.size() >= maxNumberOfLayersToCache) {
                    final Integer leastRecentlyUsedIndex = indexToLayerProcessorImageAndMask.keySet()
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("cache should have at least one element"));
                    indexToLayerProcessorImageAndMask.remove(leastRecentlyUsedIndex);
                }

                processors = loader.getMaskAndProcessor(layerIndex);
            }

            // reorder linked hash map so that most recently used is last
            indexToLayerProcessorImageAndMask.put(layerIndex, processors);

            return processors;
        }
    }

    class PathLayerLoader implements LayerLoader, Serializable {

        private final List<String> layerPathList;

        public PathLayerLoader(final List<String> layerPathList) {
            this.layerPathList = layerPathList;
        }

        @Override
        public int getNumberOfLayers() {
            return layerPathList.size();
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {
            final String layerPath = layerPathList.get(layerIndex);
            return new ImagePlus(layerPath).getProcessor().convertToFloatProcessor();
        }

		@Override
		public Pair<FloatProcessor, FloatProcessor> getMaskAndProcessor(int layerIndex) {
			FloatProcessor ip = getProcessor(layerIndex);
			float[] fakeMask = new float[ ip.getWidth() * ip.getHeight() ];
			for ( int i = 0; i < fakeMask.length; ++i )
				fakeMask[ i ] = 255.0f;
			return new ValuePair<FloatProcessor, FloatProcessor>( ip, new FloatProcessor(ip.getWidth(), ip.getHeight(), fakeMask) );
		}
    }

    class RenderLayerLoader implements LayerLoader, Serializable {

        private final String layerUrlPattern;
        private final List<Double> sortedZList;
        private final ImageProcessorCache imageProcessorCache;

        final double sigma, renderScale, relativeContentThreshold;
        private String debugFilePattern;

        public RenderLayerLoader(final String layerUrlPattern,
                                 final List<Double> sortedZList,
                                 final ImageProcessorCache imageProcessorCache,
                                 final double sigma, final double renderScale, final double relativeContentThreshold) {
            this.layerUrlPattern = layerUrlPattern;
            this.sortedZList = sortedZList;
            this.debugFilePattern = null;
            this.imageProcessorCache = imageProcessorCache;
            this.sigma = sigma;
            this.renderScale = renderScale;
            this.relativeContentThreshold = relativeContentThreshold;
        }

        @Override
        public int getNumberOfLayers() {
            return sortedZList.size();
        }

        protected ImageProcessorWithMasks getImageProcessorWithMasks(final int layerIndex) {

            final Double z = sortedZList.get(layerIndex);
            final String url = String.format(layerUrlPattern, z);

            final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);

            final File debugFile = debugFilePattern == null ? null : new File(String.format(debugFilePattern, z));

            return  Renderer.renderImageProcessorWithMasks(renderParameters,
                                                           imageProcessorCache,
                                                           debugFile);
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {

            final ImageProcessorWithMasks imageProcessorWithMasks = getImageProcessorWithMasks( layerIndex );

            // TODO: make sure it is ok to drop mask here
            return imageProcessorWithMasks.ip.convertToFloatProcessor();
        }

        public Double getFirstLayerZ() {
            return sortedZList.get(0);
        }

        public void setDebugFilePattern(final String debugFilePattern) {
            this.debugFilePattern = debugFilePattern;
        }

		@Override
		public Pair<FloatProcessor, FloatProcessor> getMaskAndProcessor(int layerIndex) {
            final ImageProcessorWithMasks imageProcessorWithMasks = getImageProcessorWithMasks( layerIndex );
			//return new ValuePair<>( imageProcessorWithMasks.ip.convertToFloatProcessor(), imageProcessorWithMasks.mask.convertToFloatProcessor() );

            return new ValuePair<>( imageProcessorWithMasks.ip.convertToFloatProcessor(), 
            processMaskAndImage(sigma, renderScale, relativeContentThreshold, imageProcessorWithMasks.ip.convertToFloatProcessor(), imageProcessorWithMasks.mask.convertToFloatProcessor() ) );
		}
    }

	public static FloatProcessor processMaskAndImage( final double sigma, final double renderScale, final double relativeContentThreshold, final FloatProcessor image, final FloatProcessor mask )
	{
		System.out.println("filtering image");
    	RandomAccessibleInterval< FloatType > imgA = ArrayImgs.floats( (float[])image.getPixels(), new long[] { image.getWidth(), image.getHeight() } );
    	RandomAccessibleInterval< FloatType > imgB = ArrayImgs.floats( (float[])mask.getPixels(), new long[] { image.getWidth(), image.getHeight() } );
    	float[] outP = new float[image.getWidth() * image.getHeight()];
    	Img< FloatType > out = ArrayImgs.floats( outP, new long[] { image.getWidth(), image.getHeight() } );;

    	weightedGauss(
    			new double[] { sigma * renderScale, sigma * renderScale },
    			Views.extendMirrorSingle( imgA ),
    			Views.extendBorder( imgB ),
    			out,
    			Runtime.getRuntime().availableProcessors() );

    	Cursor< FloatType > ic = Views.flatIterable( imgA ).cursor();
    	Cursor< FloatType > mc = Views.flatIterable( imgB ).cursor();
    	Cursor< FloatType > pc = Views.flatIterable( out ).cursor();

    	while ( pc.hasNext() )
    	{
    		final FloatType p = pc.next();
    		final FloatType m = mc.next();
    		final FloatType j = ic.next();

    		if ( m.get() < 255 )
    			p.set( 0 );
    		else
    			p.set( Math.max( 0, j.get() - p.get()));
    	}

    	//Img< FloatType > outCopy = out.copy();

    	weightedGauss(
    			new double[] { sigma * renderScale, sigma * renderScale },
    			Views.extendMirrorSingle( out ),
    			Views.extendBorder( imgB ),
    			out,
    			Runtime.getRuntime().availableProcessors() );

    	mc = Views.flatIterable( imgB ).cursor();
    	pc = Views.flatIterable( out ).cursor();

    	while ( pc.hasNext() )
    	{
    		final FloatType p = pc.next();
    		final FloatType m = mc.next();

			if ( m.get() < 255 || p.get() < relativeContentThreshold )
				p.set( 0 );
			else
				p.set( 255 );
    	}

    	return new FloatProcessor( image.getWidth(), image.getHeight(), outP );
	}

	public static void weightedGauss(
			final double[] sigmas,
			final RandomAccessible<FloatType> source,
			final RandomAccessible<FloatType> weight,
			final RandomAccessibleInterval<FloatType> output,
			final int numThreads )
	{
		final FloatType type = new FloatType();
		final RandomAccessible<FloatType> weightedSource =
				Converters.convert(source, weight, (i1,i2,o) -> o.setReal( i1.getRealDouble() * i2.getRealDouble() ), type );

		final long[] min= new long[ output.numDimensions() ];
		for ( int d = 0; d < min.length; ++d )
			min[ d ] = output.min( d );

		final RandomAccessibleInterval< FloatType > sourceTmp = Views.translate( new ArrayImgFactory<>(type).create( output ), min );
		final RandomAccessibleInterval< FloatType > weightTmp = Views.translate( new ArrayImgFactory<>(type).create( output ), min );

		Gauss3.gauss(sigmas, weightedSource, sourceTmp, 1 );
		Gauss3.gauss(sigmas, weight, weightTmp, 1 );


		final Cursor< FloatType > i = Views.flatIterable( Views.interval( source, sourceTmp ) ).cursor();
		final Cursor< FloatType > s = Views.flatIterable( sourceTmp ).cursor();
		final Cursor< FloatType > w = Views.flatIterable( weightTmp ).cursor();
		final Cursor< FloatType > o = Views.flatIterable( output ).cursor();

		while ( o.hasNext() )
		{
			final double w0 = w.next().getRealDouble();

			if ( w0 == 0 )
			{
				o.next().set( i.next() );
				s.fwd();
			}
			else
			{
				o.next().setReal( s.next().getRealDouble() / w0 );
				i.fwd();
			}
		}
	}
}
