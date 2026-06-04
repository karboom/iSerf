package me.karboom.java.iSerf.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.team.Team;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Context {
    public Agent agent;
    public Team team;
}
