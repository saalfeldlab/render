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

    private List<TransformSpec> specList;

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

    @Override
    public boolean hasLabel(final String label) {
        boolean hasLabel = super.hasLabel(label);
        if (! hasLabel) {
            for (final TransformSpec transformSpec : specList) {
                if (transformSpec.hasLabel(label)) {
                    hasLabel = true;
                    break;
                }
            }
        }
        return hasLabel;
    }

    @Override
    public void removeLabel(final String label) {
        super.removeLabel(label);
        for (final TransformSpec transformSpec : specList) {
            transformSpec.removeLabel(label);
        }
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

        final TransformSpecMetaData parentMetaData = getMetaData();
        final int startIndex = flattenedList.size();

        for (final TransformSpec spec : specList) {
            spec.flatten(flattenedList);
        }

        // merge parent meta data with all flattened children
        if (parentMetaData != null) {
            for (int i = startIndex; i < flattenedList.size(); i++) {
                final TransformSpec childSpec = flattenedList.getSpec(i);
                final TransformSpecMetaData childMetaData = childSpec.getMetaData();
                if (childMetaData == null) {
                    childSpec.setMetaData(parentMetaData);
                } else {
                    childMetaData.merge(parentMetaData);
                }
            }
        }

    }

    /**
     * Flattens this list of transform specs, filters it based upon the specified labels,
     * and returns the resulting list.
     *
     * @param  excludeAfterLastLabels         removes all transforms after the last occurrence
     *                                        of a transform with one of these labels.
     *                                        Specify as null to skip include filtering.
     *
     * @param  excludeFirstAndAllAfterLabels  removes the first transform with one of these labels
     *                                        and all following transforms.
     *                                        Specify as null to skip exclude filtering.
     *
     * @return a flattened and filtered list of these transforms.
     */
    public ListTransformSpec flattenAndFilter(final Set<String> excludeAfterLastLabels,
                                              final Set<String> excludeFirstAndAllAfterLabels) {

        final ListTransformSpec flattenedList = new ListTransformSpec();

        flatten(flattenedList);

        if ((excludeAfterLastLabels != null) && (excludeAfterLastLabels.size() > 0)) {

            final int listSize = flattenedList.specList.size();
            int lastIndexWithoutLabel = listSize;

            TransformSpec spec;
            for (int i = listSize - 1; i >= 0; i--) {
                spec = flattenedList.specList.get(i);
                if (spec.hasOneOfTheseLabels(excludeAfterLastLabels)) {
                    lastIndexWithoutLabel = i + 1;
                    break;
                }
            }

            if (lastIndexWithoutLabel < listSize) {
                flattenedList.specList = flattenedList.specList.subList(0, lastIndexWithoutLabel);
            }

        }

        if ((excludeFirstAndAllAfterLabels != null) && (excludeFirstAndAllAfterLabels.size() > 0)) {

            final int listSize = flattenedList.specList.size();
            int firstIndexWithLabel = listSize;

            TransformSpec spec;
            for (int i = 0; i < listSize; i++) {
                spec = flattenedList.specList.get(i);
                if (spec.hasOneOfTheseLabels(excludeFirstAndAllAfterLabels)) {
                    firstIndexWithLabel = i;
                    break;
                }
            }

            if (firstIndexWithLabel < listSize) {
                flattenedList.specList = flattenedList.specList.subList(0, firstIndexWithLabel);
            }

        }

        return flattenedList;
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
