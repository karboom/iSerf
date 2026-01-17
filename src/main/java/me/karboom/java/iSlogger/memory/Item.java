package me.karboom.java.iSlogger.memory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class Item {

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


    /**
     * 类型  text | image | video | audio | toolCalls | mixed | custom | tip
     */
    public String type;

    public String text;
    public String image;
    public List<ToolCall> toolCalls;
    public List<Item> mixed;

    public ObjectNode custom;

    /**
     * LLM角色： assistant | user | system | tool
     */
    public String role;


    public Integer isSegment;


    public Object formatted;
}
