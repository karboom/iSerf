package me.karboom.java.iSlogger.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;


import java.text.SimpleDateFormat;


public class JSONUtil {
    public static JsonMapper mapper;

    static {
        var factory = JsonFactory.builder()
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();

        mapper = JsonMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .build();

        // 这里可以设置一些通用的转换参数

    }

    public static ObjectNode create() {
        return mapper.createObjectNode();
    }
    public static ArrayNode createArray() {
        return mapper.createArrayNode();
    }

    public static ObjectNode parse(String data) {return parse(data, ObjectNode.class);}
    public static ArrayNode parseArray(String data) {return parse(data, ArrayNode.class);}
    public static <T> T parse(String data, Class<T> cls) {
        return mapper.readValue(data, cls);
    }

    public static String stringify(Object data) {
        return mapper.writeValueAsString(data);
    }

    public static <T> T convert(Object data, TypeReference<T> typeReference) {return mapper.convertValue(data, typeReference);}
    public static <T> T convert(Object data, Class<T> object) {
        return mapper.convertValue(data, object);
    }
    public static ObjectNode convert(Object data) {
        return convert(data, ObjectNode.class);
    }
    public static ArrayNode convertArray(Object data) {
        return convert(data, ArrayNode.class);
    }
}

