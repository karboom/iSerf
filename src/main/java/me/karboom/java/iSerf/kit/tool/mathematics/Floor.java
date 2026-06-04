package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import org.apache.commons.math3.util.FastMath;

/**
 * 向下取整运算
 */
public class Floor implements FunctionWrapper<Floor.Parameter> {

    public static String description = "对数字向下取整";

    public static class Parameter {
        @JsonPropertyDescription("要向下取整的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = FastMath.floor(params.number);
        return MathUtil.formatResult(result);
    }
}
