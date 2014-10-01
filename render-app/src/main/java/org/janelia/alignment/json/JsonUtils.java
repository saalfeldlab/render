package org.janelia.alignment.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Utilities for working with JSON data.
 *
 * @author Eric Trautman
 */
public class JsonUtils {

    /** Default GSON instance used for serializing and de-serializing data. */
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

}
