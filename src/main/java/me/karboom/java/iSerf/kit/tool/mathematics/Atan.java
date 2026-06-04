package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 反正切运算(返回弧度)
 */
public class Atan implements FunctionWrapper<Atan.Parameter> {

    public static String description = "计算数字的反正切值 (返回弧度)";

    public static class Parameter {
        @JsonPropertyDescription("正切值")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.atan(params.number);
        return MathUtil.formatResult(result);
    }
}
