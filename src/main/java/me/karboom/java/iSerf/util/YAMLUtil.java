package me.karboom.java.iSerf.util;


import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.cbor.CBORFactory;
import tools.jackson.dataformat.cbor.CBORMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.module.blackbird.BlackbirdModule;

import java.text.SimpleDateFormat;


public class YAMLUtil {
    public static YAMLMapper mapper;

    static {
        var factory = YAMLFactory.builder()

//                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();


        // 这里可以设置一些通用的转换参数
        mapper = YAMLMapper.builder(factory)
//                .addModule(new BlackbirdModule())
//                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
//                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
//                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
//                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .build();
    }

    public static ObjectNode create() {
        return mapper.createObjectNode();
    }
    public static ArrayNode createArray() {
        return mapper.createArrayNode();
    }

    public static ObjectNode parse(byte[] data) {return parse(data, ObjectNode.class);}
    public static ArrayNode parseArray(byte[] data) {return parse(data, ArrayNode.class);}
    public static <T> T parse(byte[] data, Class<T> cls) {
        return mapper.readValue(data, cls);
    }
    public static <T> T parse(byte[] data, TypeReference<T> typeReference) {return mapper.readValue(data, typeReference);}

    public static byte[] toByte(Object data) {
        return mapper.writeValueAsBytes(data);
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

