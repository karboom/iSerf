package me.karboom.java.iSerf.kit.tool.time;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.util.DataUtil;

import java.util.Map;

/**
 * 添加周期任务计划
 */
@Slf4j
public class AddPeriodicPlan implements FunctionWrapper<AddPeriodicPlan.Parameter> {

    public static String description = "当用户需要周期性执行某件事时使用，例如每天、每周、每月重复执行的任务。";

    public static class Parameter {
        @JsonPropertyDescription("任务的描述")
        @JsonProperty(required = true)
        public String description;

        @JsonPropertyDescription("cron 表达式，用于指定执行周期")
        @JsonProperty(required = true)
        public String cron;

        @JsonPropertyDescription("要执行的函数名称")
        @JsonProperty(required = true)
        public String functionName;

        @JsonPropertyDescription("函数参数")
        @JsonProperty(required = true)
        public Map<String, Object> functionParams;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var plan = Plan.builder()
                .id(DataUtil.getFlakeId())
                .description(params.description)
                .cron(params.cron)
                .functionName(params.functionName)
                .functionParams(params.functionParams)
                .function((context, p) -> {
                    log.debug("AddPeriodicPlan: Executing scheduled function: {}", params.functionName);
                })
                .build();

        ctx.agent.addPlan(params.cron, null, params.functionName, null, params.functionParams);

        log.debug("AddPeriodicPlan: Plan added: id={}, description={}, cron={}", plan.getId(), params.description, params.cron);

        return CallResult.builder()
                .llm("周期计划添加成功，ID: %s".formatted(plan.getId()))
                .build();
    }
}