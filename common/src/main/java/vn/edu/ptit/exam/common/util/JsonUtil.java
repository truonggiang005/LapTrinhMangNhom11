package vn.edu.ptit.exam.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * High-performance Jackson JSON utility.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = createObjectMapper();

    private JsonUtil() {
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }

    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing object to JSON: " + e.getMessage(), e);
        }
    }

    public static byte[] toJsonBytes(Object object) {
        if (object == null) {
            return new byte[0];
        }
        try {
            return MAPPER.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing object to JSON bytes: " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing JSON to " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing JSON bytes to " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing JSON with TypeReference: " + e.getMessage(), e);
        }
    }

    public static JsonNode readTree(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing JSON tree: " + e.getMessage(), e);
        }
    }

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        if (fromValue == null) {
            return null;
        }
        return MAPPER.convertValue(fromValue, toValueType);
    }

    public static <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        if (fromValue == null) {
            return null;
        }
        return MAPPER.convertValue(fromValue, toValueTypeRef);
    }

    public static com.fasterxml.jackson.databind.node.ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }
}
