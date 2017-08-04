package org.janelia.render.client.spark;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.janelia.alignment.spec.SectionData;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link LayerDistributor} class.
 *
 * @author Eric Trautman
 */
public class LayerDistributorTest {

    @Test
    public void testDistribute() throws Exception {

        final int numberOfBuckets = 3;
        final List<Long> tileCounts = LongStream.of(10, 10, 30, 28, 7, 9).boxed().collect(Collectors.toList());

        // should be distributed as: | 30 | 28, 7 | 10, 10, 9 |

        final List<SectionData> sectionDataList = new ArrayList<>();
        double z = 1.0;
        for (final Long tileCount : tileCounts) {
            sectionDataList.add(new SectionData(String.valueOf(z), z, tileCount, 0.0, 0.0, 0.0, 0.0));
            z += 1;
        }

        final LayerDistributor layerDistributor = new LayerDistributor(numberOfBuckets);
        final List<List<Double>> list = layerDistributor.distribute(sectionDataList);

        Assert.assertEquals("invalid number of buckets", numberOfBuckets, list.size());

        Assert.assertEquals("invalid min tile count", 29, layerDistributor.getMinBucketTileCount());
        Assert.assertEquals("invalid max tile count", 35, layerDistributor.getMaxBucketTileCount());
    }

}