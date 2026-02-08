package me.karboom.java.iSlogger.agent;

import me.karboom.java.iSlogger.memory.Item;

import java.util.List;

public class NonePersistence extends Persistence {
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
