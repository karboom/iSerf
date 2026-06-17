package me.karboom.java.iSerf.server.metaData;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存元数据存储，适用于单节点或测试场景。
 */
@Slf4j
public class MemoryMetaData implements IMetaData {

    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, String> agentStay = new ConcurrentHashMap<>();
    private final Map<String, String> teamStay = new ConcurrentHashMap<>();
    private final Map<String, List<String>> agentSubscribeNodes = new ConcurrentHashMap<>();

    @Override
    public List<Node> getNodes() {
        return new ArrayList<>(nodes.values());
    }

    @Override
    public void addNode(Node node) {
        log.debug("addNode id=%s".formatted(node.getId()));
        nodes.put(node.getId(), node);
    }

    @Override
    public void removeNode(String id) {
        log.debug("removeNode id=%s".formatted(id));
        nodes.remove(id);
    }

    @Override
    public String getAgentStay(String id) {
        return agentStay.get(id);
    }

    @Override
    public void setAgentStay(String agentId, String nodeId) {
        log.debug("setAgentStay agentId=%s nodeId=%s".formatted(agentId, nodeId));
        agentStay.put(agentId, nodeId);
    }

    @Override
    public String getTeamStay(String id) {
        return teamStay.get(id);
    }

    @Override
    public void setTeamStay(String teamId, String nodeId) {
        log.debug("setTeamStay teamId=%s nodeId=%s".formatted(teamId, nodeId));
        teamStay.put(teamId, nodeId);
    }

    @Override
    public void removeTeamStay(String teamId) {
        log.debug("removeTeamStay teamId=%s".formatted(teamId));
        teamStay.remove(teamId);
    }

    @Override
    public List<String> getAgentSubscribeNodes(String id) {
        return agentSubscribeNodes.getOrDefault(id, List.of());
    }

    @Override
    public void addAgentSubscribeNode(String agentId, String nodeId) {
        log.debug("addAgentSubscribeNode agentId=%s nodeId=%s".formatted(agentId, nodeId));
        agentSubscribeNodes.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>()).add(nodeId);
    }

    @Override
    public void removeAgentSubscribeNode(String agentId, String nodeId) {
        log.debug("removeAgentSubscribeNode agentId=%s nodeId=%s".formatted(agentId, nodeId));
        var list = agentSubscribeNodes.get(agentId);
        if (list != null) {
            list.remove(nodeId);
        }
    }
}
