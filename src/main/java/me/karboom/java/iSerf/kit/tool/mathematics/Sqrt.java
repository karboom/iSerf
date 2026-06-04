package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.ErrorUtil;
import org.apache.commons.math3.util.FastMath;

/**
 * 开方运算
 */
public class Sqrt implements FunctionWrapper<Sqrt.Parameter> {

    public static String description = "执行开方运算，计算数字的平方根";

    public static class Parameter {
        @JsonPropertyDescription("要求平方根的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        if (params.number < 0) {
            throw ErrorUtil.make("不能对负数开平方根");
        }
        var result = FastMath.sqrt(params.number);
        return MathUtil.formatResult(result);
    }
}