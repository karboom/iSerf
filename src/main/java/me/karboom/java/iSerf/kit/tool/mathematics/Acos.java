package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 反余弦运算(返回弧度)
 */
public class Acos implements FunctionWrapper<Acos.Parameter> {

    public static String description = "计算数字的反余弦值(返回弧度)";

    public static class Parameter {
        @JsonPropertyDescription("余弦值 (-1 到 1 之间)")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.acos(params.number);
        return MathUtil.formatResult(result);
    }
}
