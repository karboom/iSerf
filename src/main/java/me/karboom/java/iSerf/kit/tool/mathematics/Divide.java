package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.ErrorUtil;

/**
 * 除法运算
 */
public class Divide implements FunctionWrapper<Divide.Parameter> {

    public static String description = "执行除法运算，将两个数字相除";

    public static class Parameter {
        @JsonPropertyDescription("被除数")
        @JsonProperty(required = true)
        public Double a;

        @JsonPropertyDescription("除数")
        @JsonProperty(required = true)
        public Double b;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        if (params.b == 0) {
            throw ErrorUtil.make("除数不能为零");
        }
        var result = params.a / params.b;
        return MathUtil.formatResult(result);
    }
}