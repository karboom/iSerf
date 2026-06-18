package me.karboom.java.iSerf.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.team.Task;

import java.util.List;

/**
 * Team 持久化快照
 * 包含 Team 运行时需要恢复的完整状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamSnapshot {
    public AgentMetadata metadata;
    public List<Task> tasks;
}
