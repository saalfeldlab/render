package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.Roi;
import mpicbg.ij.FeatureTransform;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.PointMatch;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SiftBenchmark {
	public static void main(final String[] args) {
		final String file1 = "/Users/innerbergerm/Data/streak-correction/jrc_mus-cerebellum-3/z13000-0-0-0.png";
		final String file2 = "/Users/innerbergerm/Data/streak-correction/jrc_mus-cerebellum-3/z13000-0-0-1.png";

		// Parameters taken from /groups/cellmap/cellmap/render/alignment/jrc_mus-cerebellum-3/match_multi_row/stage_parameters.montage_left_right.json
		final FeatureExtractionParameters featureExtractionParameters = new FeatureExtractionParameters();
		featureExtractionParameters.fdSize = 4;
		featureExtractionParameters.maxScale = 1.0;
		featureExtractionParameters.minScale = 0.25;
		featureExtractionParameters.steps = 5;

		final MatchDerivationParameters matchDerivationParameters = new MatchDerivationParameters();
		matchDerivationParameters.matchFilter = MatchFilter.FilterType.SINGLE_SET;
		matchDerivationParameters.matchFullScaleCoverageRadius = 300.0;
		matchDerivationParameters.matchIterations = 1000;
		matchDerivationParameters.matchMaxEpsilonFullScale = 5.0f;
		matchDerivationParameters.matchMaxTrust = 4.0;
		matchDerivationParameters.matchMinCoveragePercentage = 0.0;
		matchDerivationParameters.matchMinInlierRatio = 0.0f;
		matchDerivationParameters.matchMinNumInliers = 25;
		matchDerivationParameters.matchModelType = ModelType.TRANSLATION;
		matchDerivationParameters.matchRod = 0.92f;

		final double renderScale = 0.8;
		final int clipWidth = 200;

		final CanvasFeatureExtractor extractor = CanvasFeatureExtractor.build(featureExtractionParameters);
		final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(matchDerivationParameters, renderScale);

		// Load and scale images
		final long start = System.currentTimeMillis();
		final ImagePlus fullImage1 = new ImagePlus(file1);
		final ImagePlus fullImage2 = new ImagePlus(file2);

		final int w = (int) (fullImage1.getWidth() * renderScale);
		final int h = (int) (fullImage1.getHeight() * renderScale);

		final ImagePlus scaledImage1 = new ImagePlus("Scaled image 1", fullImage1.getProcessor().resize(w, h));
		final Roi rightStrip = new Roi(w - clipWidth, 0, clipWidth, h);
		scaledImage1.setRoi(rightStrip);
		final ImagePlus image1 = scaledImage1.crop();

		final ImagePlus scaledImage2 = new ImagePlus("Scaled image 2", fullImage2.getProcessor().resize(w, h));
		final Roi leftStrip = new Roi(0, 0, clipWidth, h);
		scaledImage2.setRoi(leftStrip);
		final ImagePlus image2 = scaledImage2.crop();

		final long loadingEnd = System.currentTimeMillis();
		System.out.println("loading time: " + (loadingEnd - start) + "ms");


		final List<Feature> features1 = extractor.extractFeaturesFromImageAndMask(image1.getProcessor(), null);
		final List<Feature> features2 = extractor.extractFeaturesFromImageAndMask(image2.getProcessor(), null);
		final long featureExtractionEnd = System.currentTimeMillis();
		System.out.println("feature extraction time: " + (featureExtractionEnd - loadingEnd) + "ms");

		final CanvasMatchResult matches = matcher.deriveMatchResult(features1, features2);
		final long matchingEnd = System.currentTimeMillis();
		System.out.println("matching time: " + (matchingEnd - featureExtractionEnd) + "ms");

		final PointRoi points = new PointRoi();
		for (final PointMatch match : matches.getInlierPointMatchList()) {
			points.addPoint((int) match.getP1().getL()[0] + w - clipWidth, (int) match.getP1().getL()[1]);
		}
		System.out.println("Found " + matches.getInlierPointMatchList().size() + " matches");

		scaledImage1.setRoi(points);
		scaledImage1.show();
	}
}
