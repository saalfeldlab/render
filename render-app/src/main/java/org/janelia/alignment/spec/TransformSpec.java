package org.janelia.alignment.spec;

import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.InterpolatedCoordinateTransform;

import org.janelia.alignment.json.JsonUtils;

import com.google.gson.reflect.TypeToken;

/**
 * Abstract base for all transformation specifications.
 *
 * NOTE: The {@link org.janelia.alignment.json.TransformSpecAdapter} implementation handles
 * polymorphic deserialization for this class and is tightly coupled to it's implementation here.
 * The adapter will need to be modified any time attributes of this class are modified.
 *
 * @author Eric Trautman
 */
public abstract class TransformSpec implements Serializable {

    public static final String ID_ELEMENT_NAME = "id";
    public static final String TYPE_ELEMENT_NAME = "type";
    public static final String META_DATA_ELEMENT_NAME = "metaData";

    private final String id;
    private final String type;
    private final TransformSpecMetaData metaData;

    private transient CoordinateTransform instance;

    protected TransformSpec(final String id,
                            final String type,
                            final TransformSpecMetaData metaData) {
        this.id = id;
        this.type = type;
        this.metaData = metaData;
    }

    public boolean hasId() {
        return (id != null);
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
                final Set<String> unresolvedIdList = new HashSet<>();
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
     *
     * @throws IllegalStateException
     *   if the spec's current state prevents checking resolution.
     */
    public abstract boolean isFullyResolved() throws IllegalStateException;

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
        final Set<String> unresolvedIds = new HashSet<>();
        addUnresolvedIds(unresolvedIds);
        return unresolvedIds;
    }

    /**
     * Uses the specified map to resolve any spec references within this spec.
     *
     * @param  idToSpecMap  map of transform ids to resolved specs.
     */
    public abstract void resolveReferences(Map<String, TransformSpec> idToSpecMap);

    /**
     * Adds a flattened (fully resolved) version of this spec to the specified list.
     *
     * @param  flattenedList  list to which flattened specs should be appended.
     *
     * @throws IllegalStateException
     *   if any references have not been resolved.
     */
    public abstract void flatten(ListTransformSpec flattenedList) throws IllegalStateException;


    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    public static List<TransformSpec> fromJsonArray(final String json) {
        return JsonUtils.GSON.fromJson(json, LIST_TYPE);
    }

    public static List<TransformSpec> fromJsonArray(final Reader json) {
        return JsonUtils.GSON.fromJson(json, LIST_TYPE);
    }

    /**
     * @return the coordinate transform instance built from this spec.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    protected abstract CoordinateTransform buildInstance()
            throws IllegalArgumentException;

    /**
     * Remove cached coordinate transform instance (to force future rebuild).
     */
    protected void removeInstance() {
        instance = null;
    }

    private static final Type LIST_TYPE = new TypeToken<List<TransformSpec>>(){}.getType();

    /**
     * Create a TransformSpec from a {@link CoordinateTransform}.  The
     * {@link CoordinateTransform} has to be a {@link CoordinateTransformList},
     * an {@link InterpolatedCoordinateTransform} or a TrakEM2 compatible
     * {@link mpicbg.trakem2.transform.CoordinateTransform}.  Otherwise the
     * method returns null.
     *
     * @param transform
     * @return
     */
    static public TransformSpec create(final CoordinateTransform transform) {
        if (CoordinateTransformList.class.isInstance(transform)) {
            final ListTransformSpec listSpec = new ListTransformSpec(UUID.randomUUID().toString(), null);
            @SuppressWarnings({ "rawtypes", "unchecked" })
            final List<CoordinateTransform> transforms = ((CoordinateTransformList)transform).getList(null);
            for (final CoordinateTransform t : transforms)
                listSpec.addSpec(create(t));
            return listSpec;
        } else if (InterpolatedCoordinateTransform.class.isInstance(transform)) {
            @SuppressWarnings("rawtypes")
            final InterpolatedCoordinateTransform ab = (InterpolatedCoordinateTransform)transform;
            final CoordinateTransform a = ab.getA();
            final CoordinateTransform b = ab.getB();
            return new InterpolatedTransformSpec(UUID.randomUUID().toString(), null, create(a), create(b), ab.getLambda());
        } else if (mpicbg.trakem2.transform.CoordinateTransform.class.isInstance(transform)) {
            final mpicbg.trakem2.transform.CoordinateTransform t = (mpicbg.trakem2.transform.CoordinateTransform)transform;
            return new LeafTransformSpec(UUID.randomUUID().toString(), null, t.getClass().getCanonicalName(), t.toDataString());
            //return new LeafTransformSpec(UUID.randomUUID().toString(), null, t.getClass().getCanonicalName(), null);
        } else return null;
    }

}