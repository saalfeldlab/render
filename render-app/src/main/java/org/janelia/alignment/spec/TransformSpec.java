package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;
import org.janelia.alignment.json.JsonUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Abstract base for all transformation specifications.
 *
 * NOTE: The {@link org.janelia.alignment.json.TransformSpecAdapter} implementation handles
 * polymorphic deserialization for this class and is tightly coupled to it's implementation here.
 * The adapter will need to be modified any time attributes of this class are modified.
 *
 * @author Eric Trautman
 */
public abstract class TransformSpec {

    public static final String ID_ELEMENT_NAME = "id";
    public static final String TYPE_ELEMENT_NAME = "type";
    public static final String META_DATA_ELEMENT_NAME = "metaData";

    private String id;
    private String type;
    private TransformSpecMetaData metaData;

    private transient CoordinateTransform instance;

    protected TransformSpec(String id,
                            String type,
                            TransformSpecMetaData metaData) {
        this.id = id;
        this.type = type;
        this.metaData = metaData;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public TransformSpecMetaData getMetaData() {
        return metaData;
    }

    /**
     * @throws IllegalArgumentException
     *   if a {@link CoordinateTransform} instance cannot be created based upon this specification.
     */
    public void validate()
            throws IllegalArgumentException {
        if (instance == null) {
            if (! isFullyResolved()) {
                final Set<String> unresolvedIdList = new HashSet<String>();
                addUnresolvedIds(unresolvedIdList);
                throw new IllegalArgumentException("spec '" + id +
                                                   "' has the following unresolved references: " + unresolvedIdList);
            }
            instance = buildInstance(); // cache instance for first getInstance call
        } // else the instance is already built, so the spec is valid
    }

    /**
     * @return the {@link CoordinateTransform} instance built from this specification.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    public CoordinateTransform getInstance()
            throws IllegalArgumentException {

        if (instance == null) {
            instance = buildInstance();
        }
        return instance;
    }

    /**
     * @return true if all spec references within this spec have been resolved; otherwise false.
     */
    public abstract boolean isFullyResolved();

    /**
     * Add the ids for any unresolved spec references to the specified set.
     *
     * @param  unresolvedIds  set to which unresolved ids will be added.
     */
    public abstract void addUnresolvedIds(Set<String> unresolvedIds);

    /**
     * @return the set of unresolved spec references within this spec.
     */
    public Set<String> getUnresolvedIds() {
        final Set<String> unresolvedIds = new HashSet<String>();
        addUnresolvedIds(unresolvedIds);
        return unresolvedIds;
    }

    /**
     * Uses the specified map to resolve any spec references within this spec.
     *
     * @param  idToSpecMap  map of transform ids to resolved specs.
     */
    public abstract void resolveReferences(Map<String, TransformSpec> idToSpecMap);


    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    /**
     * @return the coordinate transform instance built from this spec.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    protected abstract CoordinateTransform buildInstance()
            throws IllegalArgumentException;

}