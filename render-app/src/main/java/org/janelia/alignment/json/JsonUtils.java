package org.janelia.alignment.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TransformSpec;

/**
 * Utilities for working with JSON data.
 *
 * @author Eric Trautman
 */
public class JsonUtils {

    /** Adapter for handling polymorphic {@link TransformSpec} data. */
    public static final TransformSpecAdapter TRANSFORM_SPEC_ADAPTER = new TransformSpecAdapter();

    /** Default GSON instance used for serializing and de-serializing data. */
    public static final Gson GSON = new GsonBuilder().
            registerTypeAdapter(TransformSpec.class, TRANSFORM_SPEC_ADAPTER).
            setPrettyPrinting().
            create();

    /**
     * Helper for de-serializing JSON arrays.
     *
     * @param <T>
     */
    public static class ArrayHelper<T> {

        private final Type type;

        public ArrayHelper() {
            this.type = new TypeToken<ArrayList<T>>(){}.getType();
        }

        public List<T> fromJson(final String json) {
            return GSON.fromJson(json, type);
        }

        public List<T> fromJson(final Reader json) {
            return GSON.fromJson(json, type);
        }
    }

}
