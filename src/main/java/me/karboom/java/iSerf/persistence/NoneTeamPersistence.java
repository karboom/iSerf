package me.karboom.java.iSerf.persistence;

import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.team.Team;

import java.util.List;
import java.util.Map;

public class NoneTeamPersistence implements ITeamPersistence {

    @Override
    public TeamSnapshot load(AgentMetadata metadata) {
        return TeamSnapshot.builder()
                .tasks(List.of())
                .build();
    }

    @Override
    public void remove(Team team) {
        // do nothing
    }

    @Override
    public void syncMetadata(Team team) {
        // do nothing
    }

    @Override
    public void syncTasks(Team team) {
        // do nothing
    }

    @Override
    public List<TeamSnapshot> search(Map<String, Object> params) {
        return List.of();
    }

    @Override
    public Boolean create(Team team) {
        return false;
    }
}
