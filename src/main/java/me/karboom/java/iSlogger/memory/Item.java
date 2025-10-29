package me.karboom.java.iSlogger.memory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class Item {
    public String id;
    /**
     * 角色： assistant | user | system | tool
     */
    public String role;
    public String text;
    public List<ToolCall> toolCalls;

    @Data
    @AllArgsConstructor
    @Builder
    public static class ToolCall {
        public String id;
        public String name;
        public HashMap<String, Object> arguments;
        public ObjectNode result;
    }
}
