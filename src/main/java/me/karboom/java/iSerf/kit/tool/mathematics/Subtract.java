package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;

/**
 * 减法运算
 */
public class Subtract implements FunctionWrapper<Subtract.Parameter> {

    public static String description = "执行减法运算，将两个数字相减";

    public static class Parameter {
        @JsonPropertyDescription("被减数")
        @JsonProperty(required = true)
        public Double a;

        @JsonPropertyDescription("减数")
        @JsonProperty(required = true)
        public Double b;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = params.a - params.b;
        return MathUtil.formatResult(result);
    }
}