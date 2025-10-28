package me.karboom.java.iSlogger.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

@Data
@AllArgsConstructor
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

    // mcp-http  mcp-cli  function
    public String type;

    public Function<HashMap<String, Object>, String> function;

    public String url;
    public HashMap<String, String> headers;

    public String command;
    public HashMap<String, String> environment;
}
