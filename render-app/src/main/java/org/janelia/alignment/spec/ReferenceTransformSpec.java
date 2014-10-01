package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;

import java.util.List;
import java.util.Map;

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

    @Override
    public boolean isFullyResolved() {
        return ((resolvedInstance != null) && (resolvedInstance.isFullyResolved()));
    }

    @Override
    public void appendUnresolvedIds(List<String> unresolvedIdList) {
        if (resolvedInstance == null) {
            unresolvedIdList.add(effectiveRefId);
        } else {
            resolvedInstance.appendUnresolvedIds(unresolvedIdList);
        }
    }

    @Override
    public void resolveReferences(Map<String, TransformSpec> idToSpecMap) {
        if (resolvedInstance == null) {
            final TransformSpec spec = idToSpecMap.get(effectiveRefId);
            if (spec != null) {
                if (spec instanceof ReferenceTransformSpec) {
                    effectiveRefId = ((ReferenceTransformSpec) spec).effectiveRefId;
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
