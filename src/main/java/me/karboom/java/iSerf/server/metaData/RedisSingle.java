package me.karboom.java.iSerf.server.metaData;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;

import java.util.List;

/**
 * Redis 单节点版本
 *
 * key 规划
 * Nodes 存储节点信息，一个 HSet。数据结构：{[id]: "ip:port"}
 * AS:id 存储 Agent 和驻留节点关系，Value。数据结构：stayNodeId
 * AN:id 存储 Agent 和订阅节点关系，LIST。数据结构：[subscribeNodeId]
 * TS:id 存储 Team 和驻留节点关系，Value。数据结构：stayNodeId
 */
@Slf4j
public class RedisSingle implements IMetaData{
    public RedisClient client;
    public StatefulRedisConnection<String, String> connection;
    public RedisCommands<String, String> commands;
    private static final String NODES_KEY = "Nodes";
    private static final String AGENT_STAY_TEMPLATE = "AS:%s";
    private static final String AGENT_SUBSCRIBE_TEMPLATE = "AN:%s";
    private static final String TEAM_STAY_TEMPLATE = "TS:%s";

    public RedisSingle(String url) {
        this.client = RedisClient.create(url);
        this.connection = client.connect();
        this.commands = connection.sync();
    }

    @Override
    public List<Node> getNodes() {
        var allEntries = commands.hgetall(NODES_KEY);
        return allEntries.entrySet().stream().map(entry -> {
            var value = entry.getValue();
            var colonIndex = value.indexOf(':');
            var ip = value.substring(0, colonIndex);
            var port = value.substring(colonIndex + 1);
            return Node.builder()
                    .id(entry.getKey())
                    .ip(ip)
                    .port(Integer.valueOf(port))
                    .build();
        }).toList();
    }

    @Override
    public void addNode(Node node) {
        var value = "%s:%s".formatted(node.ip, node.port);
        commands.hset(NODES_KEY, node.id, value);
    }

    @Override
    public void removeNode(String id) {
        commands.hdel(NODES_KEY, id);
    }

    @Override
    public String getAgentStay(String id) {
        var key = AGENT_STAY_TEMPLATE.formatted(id);
        return commands.get(key);
    }

    @Override
    public void setAgentStay(String agentId, String nodeId) {
        var key = AGENT_STAY_TEMPLATE.formatted(agentId);
        commands.set(key, nodeId);
    }

    @Override
    public List<String> getAgentSubscribeNodes(String id) {
        var key = AGENT_SUBSCRIBE_TEMPLATE.formatted(id);
        return commands.lrange(key, 0, -1);
    }

    @Override
    public void addAgentSubscribeNode(String agentId, String nodeId) {
        var key = AGENT_SUBSCRIBE_TEMPLATE.formatted(agentId);
        commands.rpush(key, nodeId);
    }

    @Override
    public void removeAgentSubscribeNode(String agentId, String nodeId) {
        var key = AGENT_SUBSCRIBE_TEMPLATE.formatted(agentId);
        commands.lrem(key, 0, nodeId);
    }

    @Override
    public String getTeamStay(String id) {
        var key = TEAM_STAY_TEMPLATE.formatted(id);
        return commands.get(key);
    }

    @Override
    public void setTeamStay(String teamId, String nodeId) {
        var key = TEAM_STAY_TEMPLATE.formatted(teamId);
        commands.set(key, nodeId);
    }

    @Override
    public void removeTeamStay(String teamId) {
        var key = TEAM_STAY_TEMPLATE.formatted(teamId);
        commands.del(key);
    }
}