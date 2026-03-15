package me.karboom.java.iSerf.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class Tool<T> {

    public Tool() {
        var superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType parameterizedType) {
            var typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length > 0 && typeArguments[0] instanceof Class<?> classType) {
                this.paramType = (Class<T>) classType;
            }
        }
    }

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
    public FunctionWrapper<T> function;

    public Class<T> paramType;

    // iFunction 特有
    public String iDirectory;

    // mcp-http特有
    public String url;
    public HashMap<String, String> headers;

    // mcp-cli 特有
    public String command;
    public HashMap<String, String> environment;
}
