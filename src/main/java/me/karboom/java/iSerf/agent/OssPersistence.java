package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.memory.Item;

import java.util.List;

public class OssPersistence extends Persistence {
    @Override
    protected List<Item> loadMemory(String agentId) {
        return List.of();
    }

    @Override
    protected void saveMemory(String agentId, List<Item> data) {

    }

    @Override
    protected List<Event> loadEvent(String agentId) {
        return List.of();
    }

    @Override
    protected void saveEvent(String agentId, List<Event> data) {

    }
}
