package org.janelia.alignment.multisem;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    private final StackId renderStackId;
    private final MatchCollectionId matchCollectionId;
    private final List<OrderedMFOVPair> unconnectedMFOVPairs;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private UnconnectedMFOVPairsForStack() {
        this(null, null);
    }

    public UnconnectedMFOVPairsForStack(final StackId renderStackId,
                                        final MatchCollectionId matchCollectionId) {
        this.renderStackId = renderStackId;
        this.matchCollectionId = matchCollectionId;
        this.unconnectedMFOVPairs = new ArrayList<>();
    }

    public StackId getRenderStackId() {
        return renderStackId;
    }

    public MatchCollectionId getMatchCollectionId() {
        return matchCollectionId;
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

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedMFOVPairsForStack.class);
}
