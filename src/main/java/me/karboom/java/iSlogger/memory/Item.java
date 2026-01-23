package me.karboom.java.iSlogger.memory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class Item {
    static public class TYPE {
        public static final String TEXT = "TEXT";
        public static final String IMAGE = "IMAGE";
        public static final String VIDEO = "VIDEO";
        public static final String AUDIO = "AUDIO";
        public static final String TOOL_CALLS = "TOOL_CALLS";
        public static final String MIXED = "MIXED";
        public static final String CUSTOM = "CUSTOM";
        public static final String TIP = "TIP";
        public static final String THINKING = "THINKING";
        public static final String ERROR = "ERROR";
    }

    static public class ROLE {
        public static final String ASSISTANT = "ASSISTANT";
        public static final String USER = "USER";
        public static final String SYSTEM = "SYSTEM";
        public static final String TOOL = "TOOL";
    }


    @Data
    @AllArgsConstructor
    @Builder
    public static class ToolCall {

        @Data
        @AllArgsConstructor
        @Builder
        @NoArgsConstructor
        public static class Result {
            public ObjectNode direct;
            public String error;
            public String llm;
        }

        public String id;
        public String name;
        public HashMap<String, Object> arguments;
        public Result result;
    }

    public String id;

    public String agentId;
    public String userId;

    public String type;

    public String text;
    public String image;
    public List<ToolCall> toolCalls;
    public List<Item> mixed;

    public ObjectNode custom;

    public String role;


    public Integer isSegment;


    public Object formatted;

    public Integer usage;
}
