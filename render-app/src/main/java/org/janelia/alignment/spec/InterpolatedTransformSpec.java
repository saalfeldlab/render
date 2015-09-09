package org.janelia.alignment.spec;

import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.InterpolatedCoordinateTransform;

/**
 * Specification for an {@link InterpolatedCoordinateTransform}.
 *
 * NOTE: Annotations on the {@link TransformSpec} implementation handle
 * polymorphic deserialization for this class.
 *
 * @author Eric Trautman
 */
public class InterpolatedTransformSpec
        extends TransformSpec {

    public static final String TYPE = "interpolated";

    private final TransformSpec a;
    private final TransformSpec b;
    private final Double lambda;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private InterpolatedTransformSpec() {
        super(null, null);
        this.a = null;
        this.b = null;
        this.lambda = null;
    }

    public InterpolatedTransformSpec(final String id,
                                     final TransformSpecMetaData metaData,
                                     final TransformSpec a,
                                     final TransformSpec b,
                                     final Double lambda) {
        super(id, metaData);
        this.a = a;
        this.b = b;
        this.lambda = lambda;
    }

    @Override
    public boolean isFullyResolved()
            throws IllegalStateException {
        return (a.isFullyResolved() && b.isFullyResolved());
    }

    @Override
    public void addUnresolvedIds(final Set<String> unresolvedIds) {
        a.addUnresolvedIds(unresolvedIds);
        b.addUnresolvedIds(unresolvedIds);
    }

    @Override
    public void resolveReferences(final Map<String, TransformSpec> idToSpecMap) {
        a.resolveReferences(idToSpecMap);
        b.resolveReferences(idToSpecMap);
    }

    @Override
    public void flatten(final ListTransformSpec flattenedList)
            throws IllegalStateException {

        flattenedList.addSpec(new InterpolatedTransformSpec(getId(),
                                                            getMetaData(),
                                                            getFlattenedComponentSpec(a),
                                                            getFlattenedComponentSpec(b),
                                                            lambda));
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        return new InterpolatedCoordinateTransform<>(a.buildInstance(),
                                                     b.buildInstance(),
                                                     lambda);
    }

    private TransformSpec getFlattenedComponentSpec(final TransformSpec spec)
            throws IllegalStateException {

        final ListTransformSpec flattenedList = new ListTransformSpec();
        spec.flatten(flattenedList);

        final TransformSpec flattenedSpec;
        if (flattenedList.size() == 1)
            flattenedSpec = flattenedList.getSpec(0);
        else
            flattenedSpec = flattenedList;

        return flattenedSpec;
    }
}
