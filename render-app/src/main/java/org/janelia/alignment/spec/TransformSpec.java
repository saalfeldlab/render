package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.InterpolatedCoordinateTransform;

import org.janelia.alignment.json.JsonUtils;

/**
 * Abstract base for all transformation specifications.
 *
 * @author Eric Trautman
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = LeafTransformSpec.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = InterpolatedTransformSpec.class, name = InterpolatedTransformSpec.TYPE),
        @JsonSubTypes.Type(value = LeafTransformSpec.class, name = LeafTransformSpec.TYPE),
        @JsonSubTypes.Type(value = ListTransformSpec.class, name = ListTransformSpec.TYPE),
        @JsonSubTypes.Type(value = ReferenceTransformSpec.class, name = ReferenceTransformSpec.TYPE) })
public abstract class TransformSpec implements Serializable {

    private final String id;
    private TransformSpecMetaData metaData;

    protected TransformSpec(final String id,
                            final TransformSpecMetaData metaData) {
        this.id = id;
        this.metaData = metaData;
    }

    public boolean hasId() {
        return (id != null);
    }

    public String getId() {
        return id;
    }

    public TransformSpecMetaData getMetaData() {
        return metaData;
    }

    public void setMetaData(final TransformSpecMetaData metaData) {
        this.metaData = metaData;
    }

    public boolean hasLabel(final String label) {
        return (metaData != null) && metaData.hasLabel(label);
    }

    public boolean hasOneOfTheseLabels(final Set<String> labels) {
        boolean hasOne = false;
        for (final String label : labels) {
            if (hasLabel(label)) {
                hasOne = true;
                break;
            }
        }
        return hasOne;
    }

    public void addLabel(final String label) {
        if (metaData == null) {
            metaData = new TransformSpecMetaData();
        }
        metaData.addLabel(label);
    }

    public void removeLabel(final String label) {
        if (metaData != null) {
            metaData.removeLabel(label);
        }
    }

    /**
     * @throws IllegalArgumentException
     *   if a {@link CoordinateTransform} instance cannot be created based upon this specification.
     */
    public void validate()
            throws IllegalArgumentException {
        if (! isFullyResolved()) {
            final Set<String> unresolvedIdList = new HashSet<>();
            addUnresolvedIds(unresolvedIdList);
            throw new IllegalArgumentException("spec '" + id +
                                               "' has the following unresolved references: " + unresolvedIdList);
        }
        buildInstance(); // building instance will force everything to be validated
    }

    /**
     * @return a new (distinct and thread safe) {@link CoordinateTransform} instance built from this specification.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    @JsonIgnore
    public CoordinateTransform getNewInstance()
            throws IllegalArgumentException {
        return buildInstance();
    }

    /**
     * @return true if all spec references within this spec have been resolved; otherwise false.
     *
     * @throws IllegalStateException
     *   if the spec's current state prevents checking resolution.
     */
    @JsonIgnore
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
    @JsonIgnore
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
        return JSON_HELPER.toJson(this);
    }

    public static TransformSpec fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<TransformSpec> fromJsonArray(final String json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        //       http://stackoverflow.com/questions/6349421/how-to-use-jackson-to-deserialise-an-array-of-objects
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TransformSpec[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<TransformSpec> fromJsonArray(final Reader json)
            throws IOException {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TransformSpec[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * @return the coordinate transform instance built from this spec.
     *
     * @throws IllegalArgumentException
     *   if the instance cannot be created.
     */
    protected abstract CoordinateTransform buildInstance()
            throws IllegalArgumentException;

//    private static final TypeReference<List<TransformSpec>> LIST_TYPE = new TypeReference<List<TransformSpec>>(){};

    /**
     * Create a TransformSpec from a {@link CoordinateTransform}.  The
     * {@link CoordinateTransform} has to be a {@link CoordinateTransformList},
     * an {@link InterpolatedCoordinateTransform} or a TrakEM2 compatible
     * {@link mpicbg.trakem2.transform.CoordinateTransform}.  Otherwise the
     * method returns null.
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

    private static final JsonUtils.Helper<TransformSpec> JSON_HELPER =
            new JsonUtils.Helper<>(TransformSpec.class);

}