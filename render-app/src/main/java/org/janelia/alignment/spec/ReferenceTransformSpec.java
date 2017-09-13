package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;

/**
 * A reference to another {@link TransformSpec} instance.
 *
 * References do not have their own id, they simply use the id of the spec they reference.
 *
 * @author Eric Trautman
 */
public class ReferenceTransformSpec extends TransformSpec {

    public static final String TYPE = "ref";

    private final String refId;

    /**
     * The effective id being referenced.
     * This starts out the same as the original reference id, but can change if
     * resolution of the original (or subsequent) id locates another reference.
     */
    private transient String effectiveRefId;
    private transient TransformSpec resolvedInstance;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private ReferenceTransformSpec() {
        super(null, null);
        this.refId = null;
        this.effectiveRefId = null;
    }

    /**
     * @param  refId  the id this specification references.
     */
    public ReferenceTransformSpec(final String refId) {
        super(null, null);
        this.refId = refId;
        this.effectiveRefId = refId;
    }

    public String getRefId() {
        return refId;
    }

    @JsonIgnore
    public String getEffectiveRefId() {
        if (effectiveRefId == null) {
            effectiveRefId = refId;
        }
        return effectiveRefId;
    }

    @Override
    public boolean isFullyResolved()
            throws IllegalStateException {
        return ((resolvedInstance != null) && (resolvedInstance.isFullyResolved()));
    }

    @Override
    public void addUnresolvedIds(final Set<String> unresolvedIds) {
        if (resolvedInstance == null) {
            unresolvedIds.add(getEffectiveRefId());
        } else {
            resolvedInstance.addUnresolvedIds(unresolvedIds);
        }
    }

    @Override
    public void resolveReferences(final Map<String, TransformSpec> idToSpecMap) {
        if (resolvedInstance == null) {
            final TransformSpec spec = idToSpecMap.get(getEffectiveRefId());
            if (spec != null) {
                if (spec instanceof ReferenceTransformSpec) {
                    effectiveRefId = ((ReferenceTransformSpec) spec).getEffectiveRefId();
                } else {
                    resolvedInstance = spec;
                }
            }
        }
    }

    @Override
    public void flatten(final ListTransformSpec flattenedList) throws IllegalStateException {
        if (! isFullyResolved()) {
            throw new IllegalStateException("cannot flatten unresolved reference to " + getEffectiveRefId());
        }
        resolvedInstance.flatten(flattenedList);
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        if (resolvedInstance == null) {
            throw new IllegalArgumentException("spec reference to id '" + refId + "' has not been resolved");
        }
        return resolvedInstance.buildInstance();
    }

}
