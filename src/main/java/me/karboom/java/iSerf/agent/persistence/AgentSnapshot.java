package me.karboom.java.iSerf.agent.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.AgentEvent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.CallCache;
import me.karboom.java.iSerf.schedule.Plan;

import java.util.List;

/**
 * Agent 持久化快照
 * 包含 Agent 运行时需要恢复的完整状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSnapshot {
    public List<AgentMessage> memories;
    public List<AgentEvent> events;
    public List<CallCache> toolCalls;
    public List<Plan> plans;
}
