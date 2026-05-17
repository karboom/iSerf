package me.karboom.java.iSerf.agent.tool;

import lombok.Data;

@Data
public class WeatherTool implements FunctionWrapper<WeatherTool.Parameter> {
    public static String description = "天气查询工具";

    @Data
    public static class Parameter {
        public String city;
        public Integer days;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        return CallResult.builder()
                .llm("Weather for %s: %d days".formatted(params.city, params.days))
                .build();
    }
}
