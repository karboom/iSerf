package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.memory.Item;
import reactor.util.function.Tuple2;

import java.util.List;

public class NonePersistence implements IPersistence {
    @Override
    public Tuple2<List<Item>, List<Event>> load(String orgId, String userId, String agentId) {
        return null;
    }

    @Override
    public void save(String orgId, String userId, String agentId, List<Event> events, List<Item> memories) {

    }
}
