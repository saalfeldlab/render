package org.janelia.alignment.json;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.janelia.alignment.spec.InterpolatedTransformSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * Handles polymorphic serialization and deserialization of {@link org.janelia.alignment.spec.TransformSpec} instances.
 *
 * NOTE: This implementation is strongly coupled to the {@link TransformSpec} implementations.
 * Any {@link TransformSpec} attribute changes must be considered here as well.

 * @author Eric Trautman
 */
public class TransformSpecAdapter
        implements JsonSerializer<TransformSpec>, JsonDeserializer<TransformSpec>  {

    private final Gson gson;
    private final Map<String, TransformSpecSubClassAdapter> specTypeRegistry;

    public TransformSpecAdapter() {

        this.gson = new Gson();
        this.specTypeRegistry = new HashMap<String, TransformSpecSubClassAdapter>();

        this.specTypeRegistry.put(InterpolatedTransformSpec.TYPE,
                                  new InterpolatedAdapter());
        this.specTypeRegistry.put(LeafTransformSpec.TYPE,
                                  new TransformSpecSubClassAdapter(LeafTransformSpec.class));
        this.specTypeRegistry.put(ListTransformSpec.TYPE,
                                  new ListAdapter());
        this.specTypeRegistry.put(ReferenceTransformSpec.TYPE,
                                  new TransformSpecSubClassAdapter(ReferenceTransformSpec.class));
    }

    @Override
    public JsonElement serialize(final TransformSpec src,
                                 final Type typeOfSrc,
                                 final JsonSerializationContext context)
            throws IllegalArgumentException {

        final TransformSpecSubClassAdapter subClassAdapter = specTypeRegistry.get(src.getType());

        if (subClassAdapter == null) {
            throw new IllegalArgumentException("missing class mapping for TransformSpec type '" + src.getType() + "'");
        }

        return subClassAdapter.serializeSpec(src, context);
    }

    @Override
    public TransformSpec deserialize(final JsonElement json,
                                     final Type typeOfT,
                                     final JsonDeserializationContext context)
            throws JsonParseException {

        final JsonObject specObject = json.getAsJsonObject();
        final String specType = getStringValue(specObject, TransformSpec.TYPE_ELEMENT_NAME);
        final TransformSpecSubClassAdapter subClassAdapter = specTypeRegistry.get(specType);

        if (subClassAdapter == null) {
            throw new JsonParseException("unknown TransformSpec type '" + specType + "' specified");
        }

        return subClassAdapter.deserializeSpec(specObject);
    }

    private String getStringValue(final JsonObject specObject,
                                  final String elementName) {
        String value = null;
        final JsonElement element = specObject.get(elementName);
        if (element != null) {
            value = element.getAsString();
        }
        return value;
    }

    private TransformSpecMetaData getMetaDataValue(final JsonObject specObject,
                                                   final String elementName) {
        TransformSpecMetaData value = null;
        final JsonElement element = specObject.get(elementName);
        if (element != null) {
            value = gson.fromJson(element, TransformSpecMetaData.class);
        }
        return value;
    }

    private TransformSpec getTransformSpecValue(final JsonObject specObject,
                                                final String elementName) {
        TransformSpec value = null;
        final JsonElement element = specObject.get(elementName);
        if (element != null) {
            value = deserialize(element, null, null);
        }
        return value;
    }

    /**
     * Sub-class adapter implementations are needed to handle deserialization of TransformSpec instances that
     * contain other (polymorphic) TransformSpec instances (e.g. interpolated and list).
     *
     * This "base" adapter class contains the default serialization (good for all instances)
     * and deserialization (good for leaf and reference instances) method implementations.
     */
    private class TransformSpecSubClassAdapter {
        private final Class<? extends TransformSpec> clazz;

        public TransformSpecSubClassAdapter(final Class<? extends TransformSpec> clazz) {
            this.clazz = clazz;
        }

        public JsonElement serializeSpec(final TransformSpec src,
                                         final JsonSerializationContext context) {
            return context.serialize(src, clazz);
        }

        public TransformSpec deserializeSpec(final JsonObject specObject) {
            return gson.fromJson(specObject, clazz);
        }
    }

    /**
     * Extension of subclass adapter for handling deserialization of interpolated specs.
     *
     * NOTE: This implementation is strongly coupled to the {@link InterpolatedTransformSpec} implementation.
     * Any {@link InterpolatedTransformSpec} attribute changes must be applied here as well.
     */
    private class InterpolatedAdapter extends TransformSpecSubClassAdapter {

        public InterpolatedAdapter() {
            super(InterpolatedTransformSpec.class);
        }

        @Override
        public InterpolatedTransformSpec deserializeSpec(final JsonObject specObject) {
            final String id = getStringValue(specObject, TransformSpec.ID_ELEMENT_NAME);
            final TransformSpecMetaData metaData = getMetaDataValue(specObject, TransformSpec.META_DATA_ELEMENT_NAME);
            final TransformSpec aSpec = getTransformSpecValue(specObject, InterpolatedTransformSpec.A_ELEMENT_NAME);
            final TransformSpec bSpec = getTransformSpecValue(specObject, InterpolatedTransformSpec.B_ELEMENT_NAME);
            final JsonElement lambdaElement = specObject.get(InterpolatedTransformSpec.LAMBDA_ELEMENT_NAME);
            final Double lambda = lambdaElement.getAsDouble();
            return new InterpolatedTransformSpec(id, metaData, aSpec, bSpec, lambda);
        }

    }

    /**
     * Extension of subclass adapter for handling deserialization of list specs.
     *
     * NOTE: This implementation is strongly coupled to the {@link ListTransformSpec} implementation.
     * Any {@link ListTransformSpec} attribute changes must be applied here as well.
     */
    private class ListAdapter extends TransformSpecSubClassAdapter {

        public ListAdapter() {
            super(ListTransformSpec.class);
        }

        @Override
        public ListTransformSpec deserializeSpec(final JsonObject specObject) {
            final String id = getStringValue(specObject, TransformSpec.ID_ELEMENT_NAME);
            final TransformSpecMetaData metaData = getMetaDataValue(specObject, TransformSpec.META_DATA_ELEMENT_NAME);
            final JsonElement specListElement = specObject.get(ListTransformSpec.SPEC_LIST_ELEMENT_NAME);

            final ListTransformSpec listTransformSpec = new ListTransformSpec(id, metaData);
            TransformSpec elementSpec;
            for (final JsonElement listElement : specListElement.getAsJsonArray()) {
                elementSpec = deserialize(listElement, null, null);
                listTransformSpec.addSpec(elementSpec);
            }

            return listTransformSpec;
        }
    }

}
