package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 绝对值运算
 */
public class Abs implements FunctionWrapper<Abs.Parameter> {

    public static String description = "计算数字的绝对值";

    public static class Parameter {
        @JsonPropertyDescription("要求绝对值的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.abs(params.number);
        return MathUtil.formatResult(result);
    }
}