package me.karboom.java.iSlogger.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class Item {
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
        public String result;
    }
}
