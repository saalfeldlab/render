package org.janelia.alignment.match.stage;

import java.util.List;

import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link StageMatchingResources} class.
 *
 * @author Eric Trautman
 */
public class StageMatchingResourcesTest {

    @Test
    public void testBuildList() throws Exception {

        final String urlTemplateString = "http://rendertest:8080/tile/{id}/render-parameters";
        final String dataFile = "src/test/resources/match-test/match_stage_montage.json";

        final List<MatchStageParameters> stageParametersList = MatchStageParameters.fromJsonArrayFile(dataFile);

        Assert.assertEquals("invalid number of stage parameters loaded",
                            5, stageParametersList.size());

        for (final MatchStageParameters stageParameters : stageParametersList) {
           LOG.debug("testBuildList: loaded {}", stageParameters.toSlug());
        }

        final List<StageMatchingResources> stageResourcesList =
                StageMatchingResources.buildList(urlTemplateString,
                                                 new FeatureStorageParameters(),
                                                 ImageProcessorCache.DISABLED_CACHE,
                                                 stageParametersList);

        Assert.assertEquals("invalid size", 5, stageResourcesList.size());

        final StageMatchingResources stage3 = stageResourcesList.get(3);
        Assert.assertTrue("stage 3 feature template should match stage 2",
                          stage3.isSiftUrlTemplateMatchesPriorStageTemplate());

        final StageMatchingResources stage4 = stageResourcesList.get(4);
        Assert.assertFalse("stage 4 feature template should NOT match stage 3",
                           stage4.isSiftUrlTemplateMatchesPriorStageTemplate());
    }

    private static final Logger LOG = LoggerFactory.getLogger(StageMatchingResourcesTest.class);
}