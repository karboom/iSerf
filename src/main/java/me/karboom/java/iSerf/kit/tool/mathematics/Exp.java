package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 指数运算(e的x次方)
 */
public class Exp implements FunctionWrapper<Exp.Parameter> {

    public static String description = "计算 e 的 x 次方";

    public static class Parameter {
        @JsonPropertyDescription("指数")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.exp(params.number);
        return MathUtil.formatResult(result);
    }
}
