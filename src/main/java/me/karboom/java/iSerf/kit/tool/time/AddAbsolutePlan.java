package me.karboom.java.iSerf.kit.tool.time;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.util.DataUtil;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 添加绝对时间计划
 */
@Slf4j
public class AddAbsolutePlan implements FunctionWrapper<AddAbsolutePlan.Parameter> {

    public static String description = "当用户需要在特定日期和时间点执行某件事时使用这个工具";

    public static class Parameter {
        @JsonPropertyDescription("任务的描述")
        @JsonProperty(required = true)
        public String description;

        @JsonPropertyDescription("ISO-8601 格式的绝对时间")
        @JsonProperty(required = true)
        public String time;

        @JsonPropertyDescription("ISO-8601 格式的时间偏移量，如 P12D")
        @JsonProperty(required = true)
        public String offset;

        @JsonPropertyDescription("要执行的函数名称")
        @JsonProperty(required = true)
        public String functionName;

        @JsonPropertyDescription("函数参数")
        @JsonProperty(required = true)
        public Map<String, Object> functionParams;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var time = LocalDateTime.parse(params.time);

        var plan = Plan.builder()
                .id(DataUtil.getFlakeId())
                .description(params.description)
                .time(time)
                .functionName(params.functionName)
                .functionParams(params.functionParams)
                .function((context, p) -> {
                    log.debug("AddAbsolutePlan: Executing scheduled function: {}", params.functionName);
                })
                .build();

        ctx.agent.addPlan(null, null, params.functionName, null, params.functionParams);

        log.debug("AddAbsolutePlan: Plan added: id={}, description={}, time={}", plan.getId(), params.description, time);

        return CallResult.builder()
                .llm("绝对时间计划添加成功，ID: %s".formatted(plan.getId()))
                .build();
    }
}