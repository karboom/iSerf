package me.karboom.java.iSerf.kit.tool.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Context;
import me.karboom.java.iSerf.agent.tool.FunctionWrapper;
import me.karboom.java.iSerf.team.Event;
import me.karboom.java.iSerf.team.Task;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.ErrorUtil;

/**
 * 完成任务，将任务状态从 DOING 改为 DONE，并记录执行结果
 */
@Slf4j
public class TaskComplete implements FunctionWrapper<TaskComplete.Parameter> {

    public static String description = "完成任务，将任务状态改为 DONE，并记录执行结果";

    public static class Parameter {
        @JsonPropertyDescription("要完成的任务 ID")
        @JsonProperty(required = true)
        public String taskId;

        @JsonPropertyDescription("任务执行结果描述")
        @JsonProperty(required = true)
        public String result;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var team = ctx.getAgent().team;

        if (team == null) {
            throw ErrorUtil.make("team context is null, this tool must be used within a team");
        }

        var task = team.tasks.stream()
                .filter(t -> t.getId().equals(params.taskId))
                .findFirst()
                .orElse(null);

        if (task == null) {
            throw ErrorUtil.make("未找到任务: %s".formatted(params.taskId));
        }

        if (!task.getStatus().equals(Task.STATUS.DOING)) {
            throw ErrorUtil.make("任务状态不是进行中，无法完成: %s (当前状态: %s)".formatted(params.taskId, task.getStatus()));
        }

        // 记录结果
        task.setResult(params.result);

        // 改状态为 DONE
        task.setStatus(Task.STATUS.DONE);

        // 发送 TASK_CHANGE 事件
        team.eventQueue.offer(Event.builder()
                .id(DataUtil.getFlakeId())
                .type(Event.TYPE.TASK_CHANGE)
                .desc("Task %s completed".formatted(params.taskId))
                .build());

        log.debug("taskComplete: taskId={}, result={}", params.taskId, params.result);

        return CallResult.builder()
                .llm("任务 %s 已完成，状态改为 DONE".formatted(params.taskId))
                .build();
    }
}
