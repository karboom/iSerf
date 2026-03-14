package me.karboom.java.iSerf.agent.persistence;

import me.karboom.java.iSerf.agent.Event;
import me.karboom.java.iSerf.agent.Message;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;

public class NonePersistence implements IPersistence {
    @Override
    public Tuple2<List<Message>, List<Event>> load(String orgId, String userId, String agentId) {
        return Tuples.of(List.of(), List.of());
    }

    @Override
    public void save(String orgId, String userId, String agentId, List<Event> events, List<Message> memories) {

    }
}
