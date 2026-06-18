package me.karboom.java.iSerf.persistence;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;

import java.util.List;
import java.util.Map;

public class NoneAgentPersistence implements IAgentPersistence {

    @Override
    public AgentSnapshot load(AgentMetadata metadata) {
        return AgentSnapshot.builder()
                .memories(List.of())
                .events(List.of())
                .toolCalls(List.of())
                .plans(List.of())
                .build();
    }

    @Override
    public void remove(Agent agent) {
        // do nothing
    }

    @Override
    public void syncMemory(Agent agent) {
        // do nothing
    }

    @Override
    public void addMemory(Agent agent) {
        // do nothing
    }

    @Override
    public void syncEvent(Agent agent) {
        // do nothing
    }

    @Override
    public void syncToolCall(Agent agent) {
        // do nothing
    }

    @Override
    public void addToolCall(Agent agent) {
        // do nothing
    }

    @Override
    public void syncPlan(Agent agent) {
        // do nothing
    }

    @Override
    public void syncMetadata(Agent agent) {
        // do nothing
    }

    @Override
    public List<AgentSnapshot> search(Map<String, Object> params) {
        return List.of();
    }

    @Override
    public Boolean create(Agent agent) {
        return false;
    }
}
