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
 * 分配或重新分配任务给指定的 Agent
 */
@Slf4j
public class TaskAssign implements FunctionWrapper<TaskAssign.Parameter> {

    public static String description = "分配或重新分配任务给指定的团队成员";

    public static class Parameter {
        @JsonPropertyDescription("要分配的任务 ID")
        @JsonProperty(required = true)
        public String taskId;

        @JsonPropertyDescription("目标成员 ID")
        @JsonProperty(required = true)
        public String agentId;
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

        // 验证目标 agent 是否存在
        var targetAgent = team.member.stream()
                .filter(agent -> agent.metadata.getId().equals(params.agentId))
                .findFirst()
                .orElse(null);

        if (targetAgent == null) {
            throw ErrorUtil.make("未找到成员: %s".formatted(params.agentId));
        }

        // 更新任务负责人
        var oldAgentId = task.getAgentId();
        task.setAgentId(params.agentId);

        // 如果任务状态为 WAITING，改为 DOING 并触发分配
        if (task.getStatus().equals(Task.STATUS.WAITING)) {
            task.setStatus(Task.STATUS.DOING);
        }

        // 发送 TASK_CHANGE 事件，触发任务执行
        team.eventQueue.offer(Event.builder()
                .id(DataUtil.getFlakeId())
                .type(Event.TYPE.TASK_CHANGE)
                .desc("Task %s reassigned from %s to %s".formatted(params.taskId, oldAgentId, params.agentId))
                .build());

        log.debug("taskAssign: taskId={}, agentId={}", params.taskId, params.agentId);

        return CallResult.builder()
                .llm("任务 %s 已分配给成员 %s".formatted(params.taskId, params.agentId))
                .build();
    }
}
