package me.karboom.java.iSerf.agent;

import reactor.util.function.Tuple2;

import java.util.List;

public interface IPersistence {

    Tuple2<List<Message>, List<Event>> load(String orgId, String userId, String agentId);

    void save(String orgId, String userId, String agentId, List<Event> events, List<Message> memories);
}
