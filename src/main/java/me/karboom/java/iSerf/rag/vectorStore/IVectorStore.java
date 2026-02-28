package me.karboom.java.iSerf.rag.vectorStore;


import me.karboom.java.iSerf.rag.IStore;

import java.util.List;
import java.util.Map;

public interface IVectorStore<T> extends IStore<T> {

    /**
     * 混合搜索

     * @param query 查询条件
     * {
     *     FIELD|in: List[Object]， （in操作，可选）
     *     FIELD: Object,  （等于，可选）
     *     FIELD|ann : List[Long],  (向量相似，可选）
     * }
     * @return 匹配结果
     */
    List<T> mixedSearch(Map<String, Object> query);
}
