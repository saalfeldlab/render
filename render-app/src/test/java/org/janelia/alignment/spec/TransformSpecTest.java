package org.janelia.alignment.spec;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import mpicbg.trakem2.transform.AffineModel2D;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the {@link TransformSpec} class.
 *
 * @author Eric Trautman
 */
public class TransformSpecTest {

    private final String lensLabel = "lens";
    private LeafTransformSpec leaf1;
    private LeafTransformSpec leaf2;
    private LeafTransformSpec leaf3;
    private ReferenceTransformSpec ref1;
    private ReferenceTransformSpec ref99;
    private ListTransformSpec list4;
    private ListTransformSpec list6;

    @BeforeEach
    public void setUp() {
        final TransformSpecMetaData lcMetaData = new TransformSpecMetaData();
        lcMetaData.addLabel(lensLabel);

        // list-6
        // -- leaf-3 (with lens label)
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

        leaf1 = new LeafTransformSpec("1", null, AFFINE_2D, "1  0  0  1  0  0");
        leaf2 = new LeafTransformSpec("2", null, AFFINE_2D, "2  0  0  2  0  0");
        leaf3 = new LeafTransformSpec("3", lcMetaData, AFFINE_2D, "3  0  0  3  0  0");

        ref1 = new ReferenceTransformSpec(leaf1.getId());
        ref99 = new ReferenceTransformSpec("99");

        list4 = new ListTransformSpec("4", null);
        list4.addSpec(leaf1);
        list4.addSpec(ref99);

        final InterpolatedTransformSpec interpolated5 = new InterpolatedTransformSpec("5", null, ref1, list4, 2.3);

        list6 = new ListTransformSpec("6", null);
        list6.addSpec(leaf3);
        list6.addSpec(interpolated5);
        list6.addSpec(list4);
    }

    @Test
    public void testJsonProcessing() {

        final String json = list6.toJson();
        assertNotNull(json, "json generation returned null string");

        final TransformSpec parsedSpec = TransformSpec.fromJson(json);
        assertNotNull(parsedSpec, "null spec returned from json parse");

        ListTransformSpec parsedListSpec = null;
        if (parsedSpec instanceof ListTransformSpec) {
            parsedListSpec = (ListTransformSpec) parsedSpec;
        } else {
            fail("returned " + parsedSpec.getClass() + " instance instead of " +
                        ListTransformSpec.class.getName() + " instance");
        }

        assertEquals(list6.size(), parsedListSpec.size(), "top level list has incorrect size");

        final TransformSpec parsedListSpecItem0 = parsedListSpec.getSpec(0);

        LeafTransformSpec parsedLeafSpec = null;
        if (parsedListSpecItem0 instanceof LeafTransformSpec) {
            parsedLeafSpec = (LeafTransformSpec) parsedListSpecItem0;
        } else {
            fail("returned " + parsedListSpecItem0.getClass() + " instance instead of " +
                        LeafTransformSpec.class.getName() + " instance");
        }

        assertTrue(parsedLeafSpec.hasLabel(lensLabel), "parsed leaf 3 spec missing label");
        assertFalse(parsedSpec.isFullyResolved(), "parsed leaf 3 spec should not be fully resolved");

        validateUnresolvedSize("after parse", parsedSpec, 2);
    }

    @Test
    public void testResolveReferencesAndFlatten() {

        validateUnresolvedSize("before resolution", list6, 2);

        final Map<String, TransformSpec> idToSpecMap = new HashMap<>();
        idToSpecMap.put(ref1.getRefId(), leaf1);
        idToSpecMap.put(ref99.getRefId(), new ReferenceTransformSpec(leaf2.getId()));

        list6.resolveReferences(idToSpecMap);

        validateUnresolvedSize("after first resolution", list6, 1);

        idToSpecMap.put(leaf2.getId(), leaf2);

        list6.resolveReferences(idToSpecMap);

        validateUnresolvedSize("after second resolution", list6, 0);

        assertTrue(list6.hasLabel(lensLabel), "parent list is missing lens label");
        assertTrue(leaf3.hasLabel(lensLabel), "leaf 3 is missing lens label");
        assertFalse(list4.hasLabel(lensLabel), "list 4 should NOT have lens label");

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

        ListTransformSpec flattenedList = new ListTransformSpec();
        list6.flatten(flattenedList);

        assertEquals(4, flattenedList.size(), "incorrect size for flattened list");
        final String[] expectedIds = {"3", "5", "1", "2"};
        TransformSpec spec;
        for (int i = 0; i < expectedIds.length; i++) {
            spec = flattenedList.getSpec(i);
            assertEquals(expectedIds[i],spec.getId(), "invalid spec id for flattened list item " + i);
        }

        flattenedList = list6.flattenAndFilter(Collections.singleton(lensLabel), null);
        assertEquals(1, flattenedList.size(),
                     "too many transforms left after including first transform");

        flattenedList = list6.flattenAndFilter(null, Collections.singleton(lensLabel));
        assertEquals(0, flattenedList.size(),
                     "no transforms should be left after excluding first transform");

        final String roughLabel = "rough";
        leaf1.addLabel(roughLabel);

        flattenedList = list6.flattenAndFilter(Collections.singleton(roughLabel), null);
        assertEquals(3, flattenedList.size(),
                     "incorrect size after including first transform");

        list4.addLabel(roughLabel);
        flattenedList = list6.flattenAndFilter(Collections.singleton(roughLabel), null);
        assertEquals(4, flattenedList.size(),
                     "incorrect size after including transform list 4");

    }

    @Test
    public void testGetNewInstance() {

        leaf1.validate();

        final mpicbg.models.CoordinateTransform coordinateTransform1 = leaf1.getNewInstance();
        final mpicbg.models.CoordinateTransform coordinateTransform2 = leaf1.getNewInstance();

        assertNotNull(coordinateTransform1, "transform 1 not created");
        assertNotNull(coordinateTransform2, "transform 2 not created");
        //noinspection ConstantConditions
        assertFalse((coordinateTransform1 == coordinateTransform2),
                    "transform instances should be different");
    }

    @Test
    public void testValidateWithUnknownClass() {
        final LeafTransformSpec spec = new LeafTransformSpec("bad-class", "1 0 0 1 0 0");
        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    public void testValidateWithNonTransformClass() {
        final LeafTransformSpec spec = new LeafTransformSpec(this.getClass().getName(), "1 0 0 1 0 0");
        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    private void validateUnresolvedSize(final String context,
                                        final TransformSpec spec,
                                        final int expectedSize) {
        final Set<String> unresolvedIds = spec.getUnresolvedIds();
        assertEquals(expectedSize, unresolvedIds.size(), "invalid number of unresolved references " + context);
    }

    private static final String AFFINE_2D = AffineModel2D.class.getName();
}
