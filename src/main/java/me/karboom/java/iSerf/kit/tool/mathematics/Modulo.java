package me.karboom.java.iSerf.kit.tool.mathematics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.util.ErrorUtil;

/**
 * 求模运算
 */
public class Modulo implements FunctionWrapper<Modulo.Parameter> {

    public static String description = "执行求模运算，计算 a 对 b 取余";

    public static class Parameter {
        @JsonPropertyDescription("被除数")
        @JsonProperty(required = true)
        public Double a;

        @JsonPropertyDescription("除数")
        @JsonProperty(required = true)
        public Double b;
    }

    @Override
    @SneakyThrows
    public CallResult run(Context ctx, Parameter params) {
        if (params.b == 0) {
            throw ErrorUtil.make("除数不能为零");
        }
        var result = params.a % params.b;
        return MathUtil.formatResult(result);
    }
}