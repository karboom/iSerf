package me.karboom.java.iSerf.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Tool {
    static public class TYPE {
        public static final String MCP_HTTP = "MCP-HTTP";
        public static final String MCP_CLI = "MCP-CLI";
        public static final String FUNCTION = "FUNCTION";
        public static final String IFUNCTION = "IFUNCTION";
    }

    @Data
    @AllArgsConstructor
    static public class Parameter {
        public String name;
        public String type;
        public String description;
        public Boolean required;
        public List<Parameter> properties;
    }

    public String name;
    public String description;
    public List<Parameter> parameters;

    public String type;

    // function 特有
    public FunctionWrapper function;
    

    // iFunction 特有
    public String iDirectory;

    // mcp-http特有
    public String url;
    public HashMap<String, String> headers;

    // mcp-cli 特有
    public String command;
    public HashMap<String, String> environment;
}
