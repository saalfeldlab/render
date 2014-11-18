package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * List of transform specifications.
 *
 * NOTE: The {@link org.janelia.alignment.json.TransformSpecAdapter} implementation handles
 * polymorphic deserialization for this class and is tightly coupled to it's implementation here.
 * The adapter will need to be modified any time attributes of this class are modified.
 *
 * @author Eric Trautman
 */
public class ListTransformSpec extends TransformSpec {

    public static final String TYPE = "list";
    public static final String SPEC_LIST_ELEMENT_NAME = "specList";

    private List<TransformSpec> specList;

    public ListTransformSpec() {
        this(null, null);
    }

    public ListTransformSpec(String id,
                             TransformSpecMetaData metaData) {
        super(id, TYPE, metaData);
        this.specList = new ArrayList<TransformSpec>();
    }

    public TransformSpec getSpec(int index) {
        return specList.get(index);
    }

    public void addSpec(TransformSpec spec) {
        specList.add(spec);
    }

    public void setSpec(int index,
                        TransformSpec spec) {
        specList.set(index, spec);
    }

    public void addAllSpecs(List<TransformSpec> specs) {
        this.specList.addAll(specs);
    }

    public int size() {
        return specList.size();
    }

    public void removeNullSpecs() {
        TransformSpec spec;
        for (Iterator<TransformSpec> i = specList.iterator(); i.hasNext();) {
            spec = i.next();
            if (spec == null) {
                i.remove();
            }
        }
    }

    @Override
    public boolean isFullyResolved()
            throws IllegalStateException {
        boolean allSpecsResolved = true;
        for (TransformSpec spec : specList) {
            if (spec == null) {
                throw new IllegalStateException("A null spec is part of the transform spec list with id '" + getId() +
                                                "'.  Check for an extraneous comma at the end of the list.");
            }
            if (! spec.isFullyResolved()) {
                allSpecsResolved = false;
                break;
            }
        }
        return allSpecsResolved;
    }

    @Override
    public void addUnresolvedIds(Set<String> unresolvedIds) {
        for (TransformSpec spec : specList) {
            spec.addUnresolvedIds(unresolvedIds);
        }
    }

    @Override
    public void resolveReferences(Map<String, TransformSpec> idToSpecMap) {
        for (TransformSpec spec : specList) {
            spec.resolveReferences(idToSpecMap);
        }
    }

    @Override
    public void flatten(ListTransformSpec flattenedList) throws IllegalStateException {
        for (TransformSpec spec : specList) {
            spec.flatten(flattenedList);
        }
    }

    @SuppressWarnings("unchecked")
    public CoordinateTransformList<CoordinateTransform> getInstanceAsList()
            throws IllegalArgumentException {
        return (CoordinateTransformList<CoordinateTransform>) super.getInstance();
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        final CoordinateTransformList<CoordinateTransform> ctList = new CoordinateTransformList<CoordinateTransform>();
        for (TransformSpec spec : specList) {
            ctList.add(spec.buildInstance());
        }
        return ctList;
    }
}
