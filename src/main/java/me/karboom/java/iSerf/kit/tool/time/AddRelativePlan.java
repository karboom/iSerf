package me.karboom.java.iSerf.kit.tool.time;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.util.DataUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 添加相对时间计划
 */
@Slf4j
public class AddRelativePlan implements FunctionWrapper<AddRelativePlan.Parameter> {

    public static String description = "当用户需要在相对于当前时间的某个时长后执行某件事时使用这个工具，例如 1 小时后、3 天后、一周后";

    public static class Parameter {
        @JsonPropertyDescription("任务的描述")
        @JsonProperty(required = true)
        public String description;

        @JsonPropertyDescription("ISO-8601 格式的时长，例如 P1H(1 小时)、P1D(1 天)、P1M(1 个月)")
        @JsonProperty(required = true)
        public String duration;

        @JsonPropertyDescription("要执行的函数名称")
        @JsonProperty(required = true)
        public String functionName;

        @JsonPropertyDescription("函数参数")
        @JsonProperty(required = true)
        public Map<String, Object> functionParams;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var duration = Duration.parse(params.duration);
        var time = LocalDateTime.now().plus(duration);

        var plan = Plan.builder()
                .id(DataUtil.getFlakeId())
                .description(params.description)
                .time(time)
                .functionName(params.functionName)
                .functionParams(params.functionParams)
                .function((context, p) -> {
                    log.debug("AddRelativePlan: Executing scheduled function: {}", params.functionName);
                })
                .build();

        ctx.agent.addPlan(null, null, params.functionName, null, params.functionParams);

        log.debug("AddRelativePlan: Plan added: id={}, description={}, duration={}, time={}", plan.getId(), params.description, params.duration, time);

        return CallResult.builder()
                .llm("相对时间计划添加成功，ID: %s".formatted(plan.getId()))
                .build();
    }
}