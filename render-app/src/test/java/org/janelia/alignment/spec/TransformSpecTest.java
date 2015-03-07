package org.janelia.alignment.spec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.json.JsonUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link TransformSpec} class.
 *
 * @author Eric Trautman
 */
public class TransformSpecTest {

    private LeafTransformSpec leaf1;
    private LeafTransformSpec leaf2;
    private LeafTransformSpec leaf3;
    private ReferenceTransformSpec ref1;
    private ReferenceTransformSpec ref99;
    private ListTransformSpec listSpec;

    @Before
    public void setUp() throws Exception {
        final TransformSpecMetaData lcMetaData = new TransformSpecMetaData();
        lcMetaData.setGroup("test");

        // list-6
        // -- leaf-3
        // -- interpolated-5
        //    -- a: ref-1
        //          -- leaf-1
        //    -- b: list-4
        //          -- leaf-1
        //          -- ref-99
        //             ** ref-2 (added after first pass to test multiple pass resolution)
        //                -- leaf-2
        // -- list-4
        //    -- leaf-1
        //    -- ref-99
        //       ** ref-2 (added after first pass to test multiple pass resolution)
        //          -- leaf-2

        leaf1 = new LeafTransformSpec("1", lcMetaData, AFFINE_2D, "1  0  0  1  0  0");
        leaf2 = new LeafTransformSpec("2", null, AFFINE_2D, "2  0  0  2  0  0");
        leaf3 = new LeafTransformSpec("3", lcMetaData, AFFINE_2D, "3  0  0  3  0  0");

        ref1 = new ReferenceTransformSpec(leaf1.getId());
        ref99 = new ReferenceTransformSpec("99");

        final ListTransformSpec list4 = new ListTransformSpec("4", null);
        list4.addSpec(leaf1);
        list4.addSpec(ref99);

        final InterpolatedTransformSpec interpolated5 = new InterpolatedTransformSpec("5", null, ref1, list4, 2.3);

        listSpec = new ListTransformSpec("6", null);
        listSpec.addSpec(leaf3);
        listSpec.addSpec(interpolated5);
        listSpec.addSpec(list4);
    }

    @Test
    public void testJsonProcessing() throws Exception {

        final String json = listSpec.toJson();

        Assert.assertNotNull("json generation returned null string", json);

        LOG.info("generated:\n" + json);

        final TransformSpec parsedSpec = JsonUtils.GSON.fromJson(json, TransformSpec.class);

        Assert.assertNotNull("null spec returned from json parse");

        ListTransformSpec parsedListSpec = null;
        if (parsedSpec instanceof ListTransformSpec) {
            parsedListSpec = (ListTransformSpec) parsedSpec;
        } else {
            Assert.fail("returned " + parsedSpec.getClass() + " instance instead of " +
                        ListTransformSpec.class.getName() + " instance");
        }

        Assert.assertEquals("top level list has incorrect size", listSpec.size(), parsedListSpec.size());

        final TransformSpec parsedListSpecItem0 = parsedListSpec.getSpec(0);

        LeafTransformSpec parsedLeafSpec = null;
        if (parsedListSpecItem0 instanceof LeafTransformSpec) {
            parsedLeafSpec = (LeafTransformSpec) parsedListSpecItem0;
        } else {
            Assert.fail("returned " + parsedListSpecItem0.getClass() + " instance instead of " +
                        LeafTransformSpec.class.getName() + " instance");
        }

        final TransformSpecMetaData parsedMetaData = parsedLeafSpec.getMetaData();
        Assert.assertNotNull("null meta data returned", parsedMetaData);

        final TransformSpecMetaData lcMetaData = leaf3.getMetaData();
        Assert.assertEquals("invalid meta data group",
                            lcMetaData.getGroup(), parsedMetaData.getGroup());

        Assert.assertFalse("parsed spec should not be fully resolved", parsedSpec.isFullyResolved());

        validateUnresolvedSize("after parse", parsedSpec, 2);
    }

    @Test
    public void testResolveReferencesAndFlatten() throws Exception {

        validateUnresolvedSize("before resolution", listSpec, 2);

        final Map<String, TransformSpec> idToSpecMap = new HashMap<String, TransformSpec>();
        idToSpecMap.put(ref1.getRefId(), leaf1);
        idToSpecMap.put(ref99.getRefId(), new ReferenceTransformSpec(leaf2.getId()));

        listSpec.resolveReferences(idToSpecMap);

        validateUnresolvedSize("after first resolution", listSpec, 1);

        idToSpecMap.put(leaf2.getId(), leaf2);

        listSpec.resolveReferences(idToSpecMap);

        validateUnresolvedSize("after second resolution", listSpec, 0);

        // Flattened structure should look like this:
        //
        // list-6
        // -- leaf-3
        // -- interpolated-5
        //    -- a: leaf-1
        //    -- b: list-4
        //          -- leaf-1
        //          -- leaf-2
        // -- leaf-1
        // -- leaf-2

        final ListTransformSpec flattenedList = new ListTransformSpec();
        listSpec.flatten(flattenedList);

        Assert.assertEquals("incorrect size for flattened list", 4, flattenedList.size());
        final String[] expectedIds = {"3", "5", "1", "2"};
        TransformSpec spec;
        for (int i = 0; i < expectedIds.length; i++) {
            spec = flattenedList.getSpec(i);
            Assert.assertEquals("invalid spec id for flattened list item " + i, expectedIds[i],spec.getId());
        }
    }

    @Test
    public void testGetInstanceWithValidation() throws Exception {
        leaf1.validate();
        final mpicbg.models.CoordinateTransform coordinateTransform = leaf1.getInstance();
        Assert.assertNotNull("transform not created after validation", coordinateTransform);
    }

    @Test
    public void testGetInstanceWithoutValidation() throws Exception {
        final mpicbg.models.CoordinateTransform coordinateTransform = leaf1.getInstance();
        Assert.assertNotNull("transform not created prior to validation", coordinateTransform);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithUnknownClass() throws Exception {
        final LeafTransformSpec spec = new LeafTransformSpec("bad-class", "1 0 0 1 0 0");
        spec.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithNonTransformClass() throws Exception {
        final LeafTransformSpec spec = new LeafTransformSpec(this.getClass().getName(), "1 0 0 1 0 0");
        spec.validate();
    }

    private void validateUnresolvedSize(final String context,
                                        final TransformSpec spec,
                                        final int expectedSize) {
        final Set<String> unresolvedIds = spec.getUnresolvedIds();
        Assert.assertEquals("invalid number of unresolved references " + context, expectedSize, unresolvedIds.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformSpecTest.class);
    private static final String AFFINE_2D = AffineModel2D.class.getName();
}
