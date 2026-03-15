package me.karboom.java.iSerf.kit.tool;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.util.DataUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
public class Time {


    public Time() {

    }

    /**
     * 添加周期任务计划工具
     * @return
     */
    public Tool addPeriodicPlan() {
        return Tool.<Map>builder()
                .name("add_periodic_plan")
                .description("当用户需要周期性执行某件事时使用，例如每天、每周、每月重复执行的任务。")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("description", "string", "任务的描述", true, null),
                        new Tool.Parameter("cron", "string", "cron 表达式，用于指定执行周期", true, null),
                        new Tool.Parameter("functionName", "string", "要执行的函数名称", true, null),
                        new Tool.Parameter("functionParams", "object", "函数参数", true, null)
                ))
                .function((ctx, params) -> {
                    var description = (String) params.get("description");
                    var cron = (String) params.get("cron");
                    var functionName = (String) params.get("functionName");
                    var functionParams = (Map<String, Object>) params.get("functionParams");

                    var plan = Plan.builder()
                            .id(DataUtil.getFlakeId())
                            .description(description)
                            .cron(cron)
                            .functionName(functionName)
                            .functionParams(functionParams)
                            .function((context, p) -> {
                                log.debug("addPeriodicPlan: Executing scheduled function: {}", functionName);
                            })
                            .build();

                    ctx.agent.addPlan(cron, null, functionName, null, functionParams);

                    log.debug("addPeriodicPlan: Plan added: id={}, description={}, cron={}", plan.getId(), description, cron);

                    return CallResult.builder()
                            .llm("周期计划添加成功，ID: %s".formatted(plan.getId()))
                            .build();
                })
                .build();
    }

    /**
     * 添加绝对时间计划工具
     * @return
     */
    public Tool addAbsolutePlan() {
        return Tool.<Map>builder()
                .name("add_absolute_plan")
                .description("当用户需要在特定日期和时间点执行某件事时使用这个工具")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("description", "string", "任务的描述", true, null),
                        new Tool.Parameter("time", "string", "ISO-8601 格式的绝对时间", true, null),
                        new Tool.Parameter("offset", "string", "ISO-8601 格式的时间偏移量，如 P12D", true, null),

                        new Tool.Parameter("functionName", "string", "要执行的函数名称", true, null),
                        new Tool.Parameter("functionParams", "object", "函数参数", true, null)
                ))
                .function((ctx, params) -> {
                    var description = (String) params.get("description");
                    var timeStr = (String) params.get("time");
                    var functionName = (String) params.get("functionName");
                    var functionParams = (Map<String, Object>) params.get("functionParams");

                    var time = LocalDateTime.parse(timeStr);

                    var plan = Plan.builder()
                            .id(DataUtil.getFlakeId())
                            .description(description)
                            .time(time)
                            .functionName(functionName)
                            .functionParams(functionParams)
                            .function((context, p) -> {
                                log.debug("addAbsolutePlan: Executing scheduled function: {}", functionName);
                            })
                            .build();

                    ctx.agent.addPlan(null, null, functionName, null, functionParams);

                    log.debug("addAbsolutePlan: Plan added: id={}, description={}, time={}", plan.getId(), description, time);

                    return CallResult.builder()
                            .llm("绝对时间计划添加成功，ID: %s".formatted(plan.getId()))
                            .build();
                })
                .build();
    }

    /**
     * 添加相对时间计划工具
     * @return
     */
    public Tool addRelativePlan() {
        return Tool.<Map>builder()
                .name("add_relative_plan")
                .description("当用户需要在相对于当前时间的某个时长后执行某件事时使用这个工具，例如 1 小时后、3 天后、一周后")
                .type(Tool.TYPE.FUNCTION)
                .parameters(List.of(
                        new Tool.Parameter("description", "string", "任务的描述", true, null),
                        new Tool.Parameter("duration", "string", "ISO-8601 格式的时长，例如 P1H(1 小时)、P1D(1 天)、P1M(1 个月)", true, null),
                        new Tool.Parameter("functionName", "string", "要执行的函数名称", true, null),
                        new Tool.Parameter("functionParams", "object", "函数参数", true, null)
                ))
                .function((ctx, params) -> {
                    var description = (String) params.get("description");
                    var durationStr = (String) params.get("duration");
                    var functionName = (String) params.get("functionName");
                    var functionParams = (Map<String, Object>) params.get("functionParams");

                    var duration = Duration.parse(durationStr);
                    var time = LocalDateTime.now().plus(duration);

                    var plan = Plan.builder()
                            .id(DataUtil.getFlakeId())
                            .description(description)
                            .time(time)
                            .functionName(functionName)
                            .functionParams(functionParams)
                            .function((context, p) -> {
                                log.debug("addRelativePlan: Executing scheduled function: {}", functionName);
                            })
                            .build();

                    ctx.agent.addPlan(null, null, functionName, null, functionParams);

                    log.debug("addRelativePlan: Plan added: id={}, description={}, duration={}, time={}", plan.getId(), description, durationStr, time);

                    return CallResult.builder()
                            .llm("相对时间计划添加成功，ID: %s".formatted(plan.getId()))
                            .build();
                })
                .build();
    }

}