package me.karboom.java.iSlogger.agent;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.util.JSONUtil;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/**
 * Redis 持久化处理器
 *
 * Todo 增加连接池
 */
@Slf4j
public class RedisPersistence extends Persistence{
    public String memoryKeyTemplate;
    public String eventKeyTemplate;
    
    public RedisClient client;

    public RedisPersistence (RedisClient client) {
        this.client = client;
        this.memoryKeyTemplate = "memory:%s";
        this.eventKeyTemplate = "event:%s";
    }

    @Override
    protected List<Item> loadMemory(String agentId) {
        var key = memoryKeyTemplate.formatted(agentId);
        var connection = client.connect();
        
        try {
            RedisCommands<String, String> commands = connection.sync();
            var json = commands.get(key);
            if (json == null) {
                return List.of();
            }
            var arrayNode = JSONUtil.parseArray(json);
            return JSONUtil.convert(arrayNode, new TypeReference<List<Item>>() {});
        } finally {
            connection.close();
        }
    }

    @Override
    protected void saveMemory(String agentId, List<Item> data) {
        var key = memoryKeyTemplate.formatted(agentId);
        var json = JSONUtil.stringify(data);
        var connection = client.connect();
        
        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.set(key, json);
        } finally {
            connection.close();
        }
    }

    @Override
    protected List<Event> loadEvent(String agentId) {
        var key = eventKeyTemplate.formatted(agentId);
        var connection = client.connect();
        
        try {
            RedisCommands<String, String> commands = connection.sync();
            var json = commands.get(key);
            if (json == null) {
                return List.of();
            }
            var arrayNode = JSONUtil.parseArray(json);
            return JSONUtil.convert(arrayNode, new TypeReference<List<Event>>() {});
        } finally {
            connection.close();
        }
    }

    @Override
    protected void saveEvent(String agentId, List<Event> data) {
        var key = eventKeyTemplate.formatted(agentId);
        var json = JSONUtil.stringify(data);
        var connection = client.connect();
        
        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.set(key, json);
        } finally {
            connection.close();
        }
    }
}
