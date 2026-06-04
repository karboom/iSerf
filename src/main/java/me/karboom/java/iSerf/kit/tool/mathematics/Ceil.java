package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 向上取整运算
 */
public class Ceil implements FunctionWrapper<Ceil.Parameter> {

    public static String description = "对数字向上取整";

    public static class Parameter {
        @JsonPropertyDescription("要向上取整的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.ceil(params.number);
        return MathUtil.formatResult(result);
    }
}
