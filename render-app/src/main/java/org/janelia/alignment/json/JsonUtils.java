package org.janelia.alignment.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.IOException;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

/**
 * Utilities for working with JSON data.
 *
 * @author Eric Trautman
 */
public class JsonUtils {

    public static final String ISO_8601_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    public static SimpleDateFormat getDateFormat() {
        final SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601_FORMAT_STRING);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dateFormat;
    }

    public static DefaultPrettyPrinter getArraysOnNewLinePrettyPrinter() {
        final DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return printer;
    }

    public static final ObjectMapper FAST_MAPPER = new ObjectMapper().
            setSerializationInclusion(JsonInclude.Include.NON_NULL).
            setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY).
            setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE).
            setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE).
            setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE).
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).
            configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false).
            setDateFormat(getDateFormat());

    public static final ObjectMapper MAPPER = FAST_MAPPER.copy().
            setDefaultPrettyPrinter(getArraysOnNewLinePrettyPrinter()).
            enable(SerializationFeature.INDENT_OUTPUT);

    public static final ObjectMapper STRICT_MAPPER = FAST_MAPPER.copy().
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true).
            configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);

    public static class Helper<T> {

        private final ObjectMapper mapper;
        private final Class<T> valueType;
        private final CollectionType collectionType;


        public Helper(final Class<T> valueType) {
            this(MAPPER, valueType);
        }

        public Helper(final ObjectMapper mapper,
                      final Class<T> valueType) {
            this.mapper = mapper;
            this.valueType = valueType;
            // see https://www.baeldung.com/jackson-collection-array
            this.collectionType = this.mapper.getTypeFactory().constructCollectionType(List.class, valueType);
        }

        public String toJson(final T value)
                throws IllegalArgumentException {
            try {
                return mapper.writeValueAsString(value);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public T fromJson(final String json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, valueType);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public T fromJson(final Reader json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, valueType);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public List<T> fromJsonArray(final String json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, collectionType);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public List<T> fromJsonArray(final Reader json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, collectionType);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

    }

    public static class GenericHelper<T> {

        private final ObjectMapper mapper;
        private final TypeReference<T> typeReference;

        public GenericHelper(final TypeReference<T> typeReference) {
            this(MAPPER, typeReference);
        }

        public GenericHelper(final ObjectMapper mapper,
                             final TypeReference<T> typeReference) {
            this.mapper = mapper;
            this.typeReference = typeReference;
        }

        public String toJson(final T value)
                throws IllegalArgumentException {
            try {
                return mapper.writeValueAsString(value);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public T fromJson(final String json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, typeReference);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public T fromJson(final Reader json)
                throws IllegalArgumentException {
            try {
                return mapper.readValue(json, typeReference);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

    }

}
