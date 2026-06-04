package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 幂运算
 */
public class Power implements FunctionWrapper<Power.Parameter> {

    public static String description = "执行幂运算，计算a的b次方";

    public static class Parameter {
        @JsonPropertyDescription("底数")
        @JsonProperty(required = true)
        public Double base;

        @JsonPropertyDescription("指数")
        @JsonProperty(required = true)
        public Double exponent;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.pow(params.base, params.exponent);
        return MathUtil.formatResult(result);
    }
}