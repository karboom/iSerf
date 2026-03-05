package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.memory.Item;
import reactor.util.function.Tuple2;

import java.util.List;

public interface IPersistence {

    Tuple2<List<Item>, List<Event>> load(String orgId, String userId, String agentId);

    void save(String orgId, String userId, String agentId, List<Event> events, List<Item> memories);
}
