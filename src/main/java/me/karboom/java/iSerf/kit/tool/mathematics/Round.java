package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;

/**
 * 四舍五入运算
 */
public class Round implements FunctionWrapper<Round.Parameter> {

    public static String description = "对数字进行四舍五入取整";

    public static class Parameter {
        @JsonPropertyDescription("要四舍五入的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var result = Math.round(params.number);
        return MathUtil.formatResult((double) result);
    }
}
