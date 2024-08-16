package org.janelia.render.client.multisem;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.RenderDataClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Auxiliary class to represent the origin of a layer in an exported multiSEM stack.
 */
class LayerOrigin {
	private final String stack;
	private final int zLayer;

	public LayerOrigin(final String stack, final int zLayer) {
		this.stack = stack;
		this.zLayer = zLayer;
	}

	public String stack() {
		return stack;
	}

	public int zLayer() {
		return zLayer;
	}

	public String project() {
		final int slabNumber = Integer.parseInt(stack.substring(1, 4));
		return getProjectForSlab(slabNumber);
	}

	public static Map<Integer, LayerOrigin> getRangeFromCsv(final String csvFile, final int start, final int end) throws IOException{
		final List<LayerOrigin> layerOrigins;
		try (final Stream<String> lines = Files.lines(Path.of(csvFile))) {
			layerOrigins = lines
					.skip(start).limit(end - start)
					.map(line -> {
						final String[] parts = line.split(",");
						return new LayerOrigin(parts[0], Integer.parseInt(parts[1]));
					}).collect(Collectors.toList());
		}

		final int actualElementsRead = layerOrigins.size();
		final Map<Integer, LayerOrigin> layerOriginMap = new HashMap<>();
		for (int i = 0; i < actualElementsRead; i++) {
			layerOriginMap.put(start + i, layerOrigins.get(i));
		}
		return layerOriginMap;
	}

	private static String getProjectForSlab(final int slabNumber) {
		final int firstProjectSlab = 10 * (slabNumber / 10);
		final int lastProjectSlab = (firstProjectSlab == 400) ? 402 : firstProjectSlab + 9;
		return String.format("slab_%03d_to_%03d", firstProjectSlab, lastProjectSlab);
	}

	public static void main(final String[] args) throws IOException {
		// Generate layer origins csv file for all of Ken's alignment stacks
		final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
		final String owner = "hess_wafer_53_center7";
		final String csvFile = "layerOrigins.csv";
		final String stackSuffix = "_hayworth_alignment_replica";

		final List<String> stacksAndLayers = new ArrayList<>();
		for (int slab = 1; slab <= 400; slab++) {
			final String project = getProjectForSlab(slab);
			final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, project);
			final List<StackId> allStacks = dataClient.getStackIds(project);
			final String stackRegex = String.format("s%03d_m\\d{3}%s", slab, stackSuffix);
			final Predicate<String> matchesPattern = Pattern.compile(stackRegex).asMatchPredicate();

			final String kensAlignmentStack = allStacks.stream()
					.map(StackId::getStack)
					.filter(matchesPattern)
					.findAny().orElseThrow();
			final String stackBaseName = kensAlignmentStack.substring(0, 9);

			final List<String> currentLayers = dataClient.getStackZValues(kensAlignmentStack).stream()
					.map(zLayer -> stackBaseName + "," + zLayer.intValue())
					.collect(Collectors.toList());

			if (slab == 213) {
				// Add an extra layer (38 of 42) for slab 213 that didn't get ingested
				final int lastLayer = currentLayers.size();
				currentLayers.add(lastLayer - 4, "MISSING,-1");
			}

			final RecapKensAlignmentTools.SlabInfo slabInfo = RecapKensAlignment.slabInfoForSlab(slab);
			final int nOriginalLayers = RecapKensAlignment.numSlices(slabInfo.stageIdPlus1()).size();
			if (currentLayers.size() != nOriginalLayers) {
				throw new IllegalStateException("Mismatch in number of layers for slab " + slab + ": " + currentLayers.size() + " vs " + nOriginalLayers);
			}

			stacksAndLayers.addAll(currentLayers);
		}

		Files.write(Path.of(csvFile), stacksAndLayers);
	}
}
