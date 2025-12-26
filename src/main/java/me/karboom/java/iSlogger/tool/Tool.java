package me.karboom.java.iSlogger.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Tool {
    @Data
    @AllArgsConstructor
    static public class Parameter {
        public String name;
        public String type;
        public String description;
        public Boolean required;
    }

    public String name;
    public String description;
    public List<Parameter> parameters;

    // mcp-http  mcp-cli  function  iClass  clazz
    public String type;

    public Function<HashMap<String, Object>, String> function;

    public String iClass;

    public String url;
    public HashMap<String, String> headers;

    public String command;
    public HashMap<String, String> environment;
}
