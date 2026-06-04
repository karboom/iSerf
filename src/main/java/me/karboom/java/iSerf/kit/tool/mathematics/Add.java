package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;

/**
 * 加法运算
 */
public class Add implements FunctionWrapper<Add.Parameter> {

    public static String description = "执行加法运算，将两个数字相加";

    public static class Parameter {
        @JsonPropertyDescription("第一个数字")
        @JsonProperty(required = true)
        public Double a;

        @JsonPropertyDescription("第二个数字")
        @JsonProperty(required = true)
        public Double b;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = params.a + params.b;
        return MathUtil.formatResult(result);
    }
}