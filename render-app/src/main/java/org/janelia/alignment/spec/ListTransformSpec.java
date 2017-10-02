package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

/**
 * List of transform specifications.
 *
 * NOTE: Annotations on the {@link TransformSpec} implementation handle
 * polymorphic deserialization for this class.
 *
 * @author Eric Trautman
 */
public class ListTransformSpec extends TransformSpec {

    public static final String TYPE = "list";

    private final List<TransformSpec> specList;

    public ListTransformSpec() {
        this(null, null);
    }

    public ListTransformSpec(final String id,
                             final TransformSpecMetaData metaData) {
        super(id, metaData);
        this.specList = new ArrayList<>();
    }

    public TransformSpec getSpec(final int index) {
        return specList.get(index);
    }

    @JsonIgnore
    public TransformSpec getLastSpec() {
        final TransformSpec lastSpec;
        if ((specList.size() > 0)) {
            lastSpec = specList.get(specList.size() - 1);
        } else {
            lastSpec = null;
        }
        return lastSpec;
    }

    public void addSpec(final TransformSpec spec) {
        specList.add(spec);
    }

    public void removeLastSpec() {
        if (specList.size() > 0) {
            specList.remove(specList.size() - 1);
        }
    }

    public void addAllSpecs(final List<TransformSpec> specs) {
        this.specList.addAll(specs);
    }

    public int size() {
        return specList.size();
    }

    public void removeNullSpecs() {
        TransformSpec spec;
        for (final Iterator<TransformSpec> i = specList.iterator(); i.hasNext();) {
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
        for (final TransformSpec spec : specList) {
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
    public void addUnresolvedIds(final Set<String> unresolvedIds) {
        for (final TransformSpec spec : specList) {
            spec.addUnresolvedIds(unresolvedIds);
        }
    }

    @Override
    public void resolveReferences(final Map<String, TransformSpec> idToSpecMap) {
        for (final TransformSpec spec : specList) {
            spec.resolveReferences(idToSpecMap);
        }
    }

    @Override
    public void flatten(final ListTransformSpec flattenedList) throws IllegalStateException {
        for (final TransformSpec spec : specList) {
            spec.flatten(flattenedList);
        }
    }

    @SuppressWarnings("unchecked")
    @JsonIgnore
    public CoordinateTransformList<CoordinateTransform> getNewInstanceAsList()
            throws IllegalArgumentException {
        return (CoordinateTransformList<CoordinateTransform>) super.getNewInstance();
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        final CoordinateTransformList<CoordinateTransform> ctList = new CoordinateTransformList<>();
        for (final TransformSpec spec : specList) {
            ctList.add(spec.buildInstance());
        }
        return ctList;
    }
}
