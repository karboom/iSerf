package me.karboom.java.iSerf.rag.store;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 IStore 实现
 */
@Slf4j
public class MemoryStore<T> implements IStore<T> {

    private final ConcurrentHashMap<String, T> store = new ConcurrentHashMap<>();

    // region create
    @Override
    public List<T> create(List<T> data) {
        log.debug(" create inserting {} entities", data.size());

        data.forEach(item -> {
            var id = extractId(item);
            store.put(id, item);
        });

        log.debug(" create inserted {} entities", data.size());
        return data;
    }
    // endregion

    // region update
    @Override
    public void updateByIds(List<String> ids, T data) {
        log.debug(" updateByIds updating {} entities with ids: {}", ids.size(), ids);

        ids.forEach(id -> store.put(id, data));

        log.debug(" updateByIds updated {} entities", ids.size());
    }
    // endregion

    // region get
    @Override
    public List<T> getByIds(List<String> ids) {
        if (ids.isEmpty()) {
            log.debug(" getByIds returning all {} entities", store.size());
            return new ArrayList<>(store.values());
        }

        log.debug(" getByIds getting {} entities with ids: {}", ids.size(), ids);
        var results = ids.stream()
                .map(store::get)
                .filter(item -> item != null)
                .toList();

        log.debug(" getByIds retrieved {} entities", results.size());
        return new ArrayList<>(results);
    }
    // endregion

    // region delete
    @Override
    public void deleteByIds(List<String> ids) {
        log.debug(" deleteByIds deleting {} entities with ids: {}", ids.size(), ids);

        ids.forEach(store::remove);

        log.debug(" deleteByIds deleted {} entities", ids.size());
    }
    // endregion

    // region mixedSearch
    @Override
    public List<T> mixedSearch(Map<String, Object> query) {
        log.debug(" mixedSearch starting mixed search with query: {}", query);

        var results = store.values().stream()
                .filter(item -> matchQuery(item, query))
                .toList();

        log.debug(" mixedSearch retrieved {} results", results.size());
        return new ArrayList<>(results);
    }

    private boolean matchQuery(T item, Map<String, Object> query) {
        var itemNode = JSONUtil.convert(item);

        for (var entry : query.entrySet()) {
            var key = entry.getKey();
            var expectedValue = entry.getValue();

            if (key.endsWith("|ann")) {
                // MemoryStore 不支持向量搜索，跳过
                continue;
            } else if (key.endsWith("|in")) {
                var fieldName = key.substring(0, key.length() - 3);
                var fieldValue = itemNode.path(fieldName);
                if (fieldValue.isMissingNode() || fieldValue.isNull()) {
                    return false;
                }
                if (expectedValue instanceof List<?> list) {
                    var actualStr = fieldValue.asText();
                    var matched = list.stream()
                            .anyMatch(v -> v.toString().equals(actualStr));
                    if (!matched) {
                        return false;
                    }
                }
            } else {
                var fieldValue = itemNode.path(key);
                if (fieldValue.isMissingNode() || fieldValue.isNull()) {
                    return false;
                }
                var actualStr = fieldValue.asText();
                var expectedStr = expectedValue instanceof String ? (String) expectedValue : expectedValue.toString();
                if (!actualStr.equals(expectedStr)) {
                    return false;
                }
            }
        }
        return true;
    }
    // endregion

    private String extractId(T item) {
        var node = JSONUtil.convert(item);
        var idNode = node.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull()) {
            return idNode.asText();
        }
        return me.karboom.java.iSerf.util.DataUtil.getFlakeId();
    }
}
