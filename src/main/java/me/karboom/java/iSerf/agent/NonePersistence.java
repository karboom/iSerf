package me.karboom.java.iSerf.agent;

import reactor.util.function.Tuple2;

import java.util.List;

public class NonePersistence implements IPersistence {
    @Override
    public Tuple2<List<Message>, List<Event>> load(String orgId, String userId, String agentId) {
        return null;
    }

    @Override
    public void save(String orgId, String userId, String agentId, List<Event> events, List<Message> memories) {

    }
}
