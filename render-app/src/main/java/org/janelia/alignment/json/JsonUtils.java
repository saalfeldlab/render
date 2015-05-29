package org.janelia.alignment.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.janelia.alignment.spec.TransformSpec;

/**
 * Utilities for working with JSON data.
 *
 * @author Eric Trautman
 */
public class JsonUtils {

    public static final String ISO_8601_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    /** Adapter for handling polymorphic {@link TransformSpec} data. */
    public static final TransformSpecAdapter TRANSFORM_SPEC_ADAPTER = new TransformSpecAdapter();

    /** Default GSON instance used for serializing and de-serializing data. */
    public static final Gson GSON = new GsonBuilder().
            registerTypeAdapter(TransformSpec.class, TRANSFORM_SPEC_ADAPTER).
            setDateFormat(ISO_8601_FORMAT_STRING).
            setPrettyPrinting().
            create();
}
