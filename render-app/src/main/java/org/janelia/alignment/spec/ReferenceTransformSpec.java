package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;

import java.util.Map;
import java.util.Set;

/**
 * A reference to another {@link TransformSpec} instance.
 *
 * References do not have their own id, they simply use the id of the spec they reference.
 *
 * @author Eric Trautman
 */
public class ReferenceTransformSpec extends TransformSpec {

    public static final String TYPE = "ref";

    private String refId;

    /**
     * The effective id being referenced.
     * This starts out the same as the original reference id, but can change if
     * resolution of the original (or subsequent) id locates another reference.
     */
    private transient String effectiveRefId;
    private transient TransformSpec resolvedInstance;

    /**
     * @param  refId  the id this specification references.
     */
    public ReferenceTransformSpec(String refId) {
        super(null, TYPE, null);
        this.refId = refId;
        this.effectiveRefId = refId;
    }

    public String getRefId() {
        return refId;
    }

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
    public void addUnresolvedIds(Set<String> unresolvedIds) {
        if (resolvedInstance == null) {
            unresolvedIds.add(getEffectiveRefId());
        } else {
            resolvedInstance.addUnresolvedIds(unresolvedIds);
        }
    }

    @Override
    public void resolveReferences(Map<String, TransformSpec> idToSpecMap) {
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
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        if (resolvedInstance == null) {
            throw new IllegalArgumentException("spec reference '" + getId() + " has not been resolved");
        }
        return resolvedInstance.buildInstance();
    }

}
