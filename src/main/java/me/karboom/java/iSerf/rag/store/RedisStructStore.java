package me.karboom.java.iSerf.rag.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisStructStore<T> implements IStructStore<T> {

    public RedisClient client;
    public RedisCommands<String, String> commands;
    public String keyPrefix;
    public Class<T> dataClass;

    public RedisStructStore(RedisClient client, String keyPrefix) {
        this.client = client;
        this.commands = client.connect().sync();
        this.keyPrefix = keyPrefix;
        var superClass = (ParameterizedType) getClass().getGenericSuperclass();
        this.dataClass = (Class<T>) superClass.getActualTypeArguments()[0];
    }

    @Override
    public List<T> create(List<T> data) {
        log.debug("create: Inserting {} entities", data.size());

        data.forEach(item -> {
            var json = JSONUtil.stringify(item);
            var obj = JSONUtil.convert(item);
            var id = obj.get("id").asString();
            var key = buildKey(id);
            commands.set(key, json);
        });

        log.debug("create: Inserted {} entities", data.size());
        return data;
    }

    @Override
    public void updateByIds(List<String> ids, T data) {
        log.debug("updateByIds: Updating {} entities with ids: {}", ids.size(), ids);

        var json = JSONUtil.stringify(data);
        ids.forEach(id -> {
            var key = buildKey(id);
            commands.set(key, json);
        });

        log.debug("updateByIds: Updated {} entities", ids.size());
    }

    @Override
    public List<T> getByIds(List<String> ids) {
        log.debug("getByIds: Getting {} entities with ids: {}", ids.size(), ids);
        var results = new ArrayList<T>();
        ids.forEach(id -> {
            var key = buildKey(id);
            var json = commands.get(key);
            if (json != null) {
                var item = JSONUtil.parse(json, dataClass);
                results.add(item);
            }
        });

        log.debug("getByIds: Retrieved {} entities", results.size());
        return results;
    }

    @Override
    public void deleteByIds(List<String> ids) {
        log.debug("deleteByIds: Deleting {} entities with ids: {}", ids.size(), ids);

        ids.forEach(id -> {
            var key = buildKey(id);
            commands.del(key);
        });

        log.debug("deleteByIds: Deleted {} entities", ids.size());
    }

    private String buildKey(String id) {
        return keyPrefix.formatted(id);
    }

    private String generateId(T item) {
        var node = JSONUtil.convert(item);
        var idNode = node.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            return idNode.asText();
        }
        return me.karboom.java.iSerf.util.DataUtil.getFlakeId();
    }
}