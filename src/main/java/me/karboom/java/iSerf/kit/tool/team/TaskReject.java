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

import java.time.OffsetDateTime;
import java.util.ArrayList;

/**
 * 智能体打回某个任务，将任务状态从 DOING 改为 WAITING，并添加评论说明打回原因
 */
@Slf4j
public class TaskReject implements FunctionWrapper<TaskReject.Parameter> {

    public static String description = "打回某个任务，将其状态从 DOING 改为 WAITING，并附上打回原因的评论";

    public static class Parameter {
        @JsonPropertyDescription("要打回的任务 ID")
        @JsonProperty(required = true)
        public String taskId;

        @JsonPropertyDescription("打回原因")
        @JsonProperty(required = true)
        public String reason;
    }

    @Override
    public CallResult run(Context ctx, Parameter params) {
        var team = ctx.team;

        var task = team.tasks.stream()
                .filter(t -> t.getId().equals(params.taskId))
                .findFirst()
                .orElse(null);

        if (task == null) {
            throw ErrorUtil.make("未找到任务: %s".formatted(params.taskId));
        }

        if (task.getStatus().equals(Task.STATUS.WAITING)) {
            throw ErrorUtil.make("任务已在等待状态，无需重复打回: %s".formatted(params.taskId));
        }

        // 改状态为 WAITING
        task.setStatus(Task.STATUS.WAITING);

        // 添加评论
        var comment = Task.Comment.builder()
                .agentId(ctx.agent.id)
                .content(params.reason)
                .createTime(OffsetDateTime.now())
                .build();
        if (task.getComments() == null) {
            task.setComments(new ArrayList<>());
        }
        task.getComments().add(comment);

        // 发送 TASK_CHANGE 事件
        team.eventQueue.offer(Event.builder()
                .id(DataUtil.getFlakeId())
                .type(Event.TYPE.TASK_CHANGE)
                .desc("Task %s rejected: %s".formatted(params.taskId, params.reason))
                .build());

        // 同时发送 COMMENT 事件
        team.eventQueue.offer(Event.builder()
                .id(DataUtil.getFlakeId())
                .type(Event.TYPE.COMMENT)
                .desc("Task %s comment: %s".formatted(params.taskId, params.reason))
                .build());

        log.debug("taskReject: taskId={}, reason={}", params.taskId, params.reason);

        return CallResult.builder()
                .llm("任务 %s 已打回，状态改为 WAITING".formatted(params.taskId))
                .build();
    }
}