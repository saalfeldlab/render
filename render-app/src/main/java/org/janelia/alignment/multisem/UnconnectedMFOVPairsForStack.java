package org.janelia.alignment.multisem;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Information about unconnected MFOV pairs in a stack.
 *
 * @author Eric Trautman
 */
public class UnconnectedMFOVPairsForStack
        implements Serializable {

    /** Acquisition stack that contains source tile specs. */
    private final StackId renderStackId;

    /** Name of stack that contains (or will contain) single layer MFOVs that have been stitched in isolation. */
    private final String mFOVMontageStackName;

    /** Match collection that is missing cross-layer matches for SFOV tile pairs within the unconnected MFOVs. */
    private final MatchCollectionId matchCollectionId;

    /** List of unconnected (or poorly connected) MFOV pairs. */
    private final List<OrderedMFOVPair> unconnectedMFOVPairs;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private UnconnectedMFOVPairsForStack() {
        this(null, null, null);
    }

    public UnconnectedMFOVPairsForStack(final StackId renderStackId,
                                        final String mFOVMontageStackName,
                                        final MatchCollectionId matchCollectionId) {
        this.renderStackId = renderStackId;
        this.mFOVMontageStackName = mFOVMontageStackName;
        this.matchCollectionId = matchCollectionId;
        this.unconnectedMFOVPairs = new ArrayList<>();
    }

    public StackId getRenderStackId() {
        return renderStackId;
    }

    public String getmFOVMontageStackName() {
        return mFOVMontageStackName;
    }

    public MatchCollectionId getMatchCollectionId() {
        return matchCollectionId;
    }

    public List<OrderedMFOVPair> getUnconnectedMFOVPairs() {
        return unconnectedMFOVPairs;
    }

    public List<LayerMFOV> getOrderedDistinctUnconnectedMFOVs() {
        final Set<LayerMFOV> distinctMFOVs = new HashSet<>();
        for (final OrderedMFOVPair pair : unconnectedMFOVPairs) {
            distinctMFOVs.add(pair.getP());
            distinctMFOVs.add(pair.getQ());
        }
        return distinctMFOVs.stream().sorted().collect(Collectors.toList());
    }

    public int size() {
        return unconnectedMFOVPairs.size();
    }

    public void addUnconnectedPair(final OrderedMFOVPair unconnectedPair) {
        unconnectedMFOVPairs.add(unconnectedPair);
    }

    public static List<UnconnectedMFOVPairsForStack> load(final String dataFile)
            throws IOException, IllegalArgumentException {

        final List<UnconnectedMFOVPairsForStack> list;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            list = Arrays.asList(JsonUtils.MAPPER.readValue(reader, UnconnectedMFOVPairsForStack[].class));
        }

        LOG.info("load: exit, loaded {} pairs", list.size());

        return list;
    }

    /**
     * @return specified stack pairs split in a list of pair groups, grouped by pZ.
     */
    public static List<UnconnectedMFOVPairsForStack> groupByPZ(final UnconnectedMFOVPairsForStack pairsForStack) {

        final Map<Double, List<OrderedMFOVPair>> zToPairs = new HashMap<>();
        for (final OrderedMFOVPair pair : pairsForStack.getUnconnectedMFOVPairs()) {
            final Double pZ = pair.getP().getZ();
            final List<OrderedMFOVPair> pairsForZ = zToPairs.computeIfAbsent(pZ, k -> new ArrayList<>());
            pairsForZ.add(pair);
        }

        final List<UnconnectedMFOVPairsForStack> list = new ArrayList<>();
        for (final Double pZ : zToPairs.keySet().stream().sorted().collect(Collectors.toList())) {
            final UnconnectedMFOVPairsForStack pairsForZ =
                    new UnconnectedMFOVPairsForStack(pairsForStack.renderStackId,
                                                     pairsForStack.mFOVMontageStackName,
                                                     pairsForStack.matchCollectionId);
            pairsForZ.unconnectedMFOVPairs.addAll(zToPairs.get(pZ));
            list.add(pairsForZ);
        }
        
        return list;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedMFOVPairsForStack.class);
}
