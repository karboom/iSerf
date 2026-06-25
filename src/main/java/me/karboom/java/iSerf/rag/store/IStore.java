package me.karboom.java.iSerf.rag.store;

import java.util.List;
import java.util.Map;

public interface IStore<T> {

    List<T> create(List<T> data);
    default T create(T data) {
        return create(List.of(data)).getFirst();
    };

    void updateByIds(List<String> ids, T data);
    default void updateById(String id, T data) {
        updateByIds(List.of(id), data);
    }

    List<T> getByIds(List<String> ids);
    default T getById(String id) {
        return getByIds(List.of(id)).getFirst();
    }

    void deleteByIds(List<String> ids);
    default void deleteById(String id) {
        deleteByIds(List.of(id));
    }

    /**
     * 混合搜索（向量+过滤条件）
     * 默认实现抛出异常，由支持向量搜索的实现覆盖
     */
    default List<T> mixedSearch(Map<String, Object> query) {
        throw new UnsupportedOperationException("mixedSearch not supported by this store");
    }
}
