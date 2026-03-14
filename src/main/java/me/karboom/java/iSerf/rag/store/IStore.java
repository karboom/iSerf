package me.karboom.java.iSerf.rag.store;

import java.util.List;

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
}
