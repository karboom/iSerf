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
 * 自然对数运算(以e为底)
 */
public class Log implements FunctionWrapper<Log.Parameter> {

    public static String description = "计算数字的自然对数(以e为底)";

    public static class Parameter {
        @JsonPropertyDescription("要求自然对数的数字")
        @JsonProperty(required = true)
        public Double number;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        if (params.number <= 0) {
            throw ErrorUtil.make("数字必须大于零");
        }
        var result = FastMath.log(params.number);
        return MathUtil.formatResult(result);
    }
}
