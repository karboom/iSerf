package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 最小值运算
 */
public class Min implements FunctionWrapper<Min.Parameter> {

    public static String description = "返回两个数字中较小的一个";

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
        var result = FastMath.min(params.a, params.b);
        return MathUtil.formatResult(result);
    }
}
