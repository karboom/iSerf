package me.karboom.java.iSerf.rag.store;

import com.google.gson.Gson;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.util.JSONUtil;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class MilvusVectorStore<T> implements IVectorStore<T> {

    public String table;
    public MilvusClientV2 client;
    public Class<T> entityClass;

    public static final String FIELD_ID = "id";
    public static final List<String> FIELD_VECTORS = new ArrayList<>();

    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_VECTOR_DIMENSION = 1536;



    public MilvusVectorStore(MilvusClientV2 client, String table) {
        this.client = client;
        this.table = table;

        // 通过反射获取泛型类型 T 的 Class 对象
        ParameterizedType superClass = (ParameterizedType) getClass().getGenericSuperclass();

        this.entityClass = (Class<T>) superClass.getActualTypeArguments()[0];


        // 通过反射获取所有 List<Float> 类型的字段
        findVectorFields(entityClass);

        log.debug("MilvusVectorStore constructor: Initialized with entityClass={}, table={}, vectorFields={}",
                entityClass != null ? entityClass.getSimpleName() : "Object", table, FIELD_VECTORS);
    }

    /**
     * 通过反射寻找所有 List<Float> 类型的字段
     */
    private void findVectorFields(Class<T> clazz) {
        FIELD_VECTORS.clear();
        for (Field field : clazz.getDeclaredFields()) {
            var genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                var rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?> classType && List.class.isAssignableFrom(classType)) {
                    var typeArguments = parameterizedType.getActualTypeArguments();
                    if (typeArguments.length == 1 && typeArguments[0] instanceof Class<?> argClass) {
                        if (Float.class.isAssignableFrom(argClass) || float.class.isAssignableFrom(argClass)) {
                            FIELD_VECTORS.add(field.getName());
                        }
                    }
                }
            }
        }
        log.debug("findVectorFields: Found {} vector fields: {}", FIELD_VECTORS.size(), FIELD_VECTORS);
    }

    @Override
    public List<T> create(List<T> data) {
        log.debug("create: Inserting {} entities", data.size());
        
        // 将数据转换为 ObjectNode 列表
        var dataList = data.stream()
                .map(item -> new Gson().toJsonTree((item)).getAsJsonObject())
                .collect(Collectors.toList());
        
        // 构建插入请求
        var insertRequest = io.milvus.v2.service.vector.request.InsertReq.builder()
                .collectionName(table)
                .data(dataList)
                .build();
        
        var insertResponse = client.insert(insertRequest);
        log.debug("create: Inserted {} entities", insertResponse.getInsertCnt());
        
        return data;
    }

    @Override
    public void updateByIds(List<String> ids, T data) {
        log.debug("updateByIds: Updating {} entities with ids: {}", ids.size(), ids);

        // 将数据转换为 ObjectNode
        var dataNode = new Gson().toJsonTree((data)).getAsJsonObject();
        
        // 使用 upsert API 更新数据
        var upsertRequest = io.milvus.v2.service.vector.request.UpsertReq.builder()
                .collectionName(table)
                .data(List.of(dataNode))
                .build();
        
        var upsertResponse = client.upsert(upsertRequest);
        log.debug("updateByIds: Upserted {} entities", upsertResponse.getUpsertCnt());
    }

    @Override
    public List<T> getByIds(List<String> ids) {
        log.debug("getByIds: Getting {} entities with ids: {}", ids.size(), ids);
        
        // 构建查询请求
        var filter = ids.stream()
                .map(id -> FIELD_ID + " in [\"" + id + "\"]")
                .collect(Collectors.joining(" or "));
        
        var queryRequest = io.milvus.v2.service.vector.request.QueryReq.builder()
                .collectionName(table)
                .filter(FIELD_ID + " in " + JSONUtil.stringify(ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"))))
                .outputFields(Collections.singletonList("*"))
                .build();
        
        var queryResponse = client.query(queryRequest);
        var queryResults = queryResponse.getQueryResults();
        
        var results = new ArrayList<T>();
        for (var result : queryResults) {
            var resultNode = JSONUtil.convert(result);
            var item = JSONUtil.convert(resultNode, entityClass);
            results.add(item);
        }
        
        log.debug("getByIds: Retrieved {} entities", results.size());
        return results;
    }

    @Override
    public void deleteByIds(List<String> ids) {
        log.debug("deleteByIds: Deleting {} entities with ids: {}", ids.size(), ids);
        
        // 构建删除请求
        var filter = FIELD_ID + " in " + JSONUtil.stringify(ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]")));
        
        var deleteRequest = io.milvus.v2.service.vector.request.DeleteReq.builder()
                .collectionName(table)
                .ids(ids.stream().map(Long::parseLong).collect(Collectors.toList()))
                .build();
        
        var deleteResponse = client.delete(deleteRequest);
        log.debug("deleteByIds: Deleted {} entities", deleteResponse.getDeleteCnt());
    }


    /**
     * 混合搜索
     * 1. 使用 search API v2 实现逻辑
     * 2. 暂时认为只有一个向量字段
     */
    @Override
    public List<T> mixedSearch(Map<String, Object> query) {
        log.debug("mixedSearch: Starting mixed search with query: {}", query);

        // 构建向量搜索数据和过滤条件
        List<BaseVector> vectors = new ArrayList<>();
        StringBuilder filterBuilder = new StringBuilder();
        boolean hasFilter = false;

        for (var entry : query.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            if (key.endsWith("|ann")) {
                // 向量相似搜索
                var vectorField = key.substring(0, key.length() - 4);
                if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number) {
                    var floatArray = list.stream()
                            .mapToDouble(v -> ((Number) v).floatValue())
                            .toArray();
                    var floatArr = new float[floatArray.length];
                    for (int i = 0; i < floatArray.length; i++) {
                        floatArr[i] = (float) floatArray[i];
                    }
                    vectors.add(new FloatVec(floatArr));
                }
            } else if (key.endsWith("|in")) {
                // IN 条件
                var fieldName = key.substring(0, key.length() - 3);
                if (value instanceof List<?> list && !list.isEmpty()) {
                    if (hasFilter) {
                        filterBuilder.append(" and ");
                    }
                    var inValues = list.stream()
                            .map(v -> v instanceof String ? "\"" + v + "\"" : v.toString())
                            .collect(Collectors.joining(", ", "[", "]"));
                    filterBuilder.append(fieldName).append(" in ").append(inValues);
                    hasFilter = true;
                }
            } else {
                // 等于条件
                if (hasFilter) {
                    filterBuilder.append(" and ");
                }
                var fieldValue = value instanceof String ? "\"" + value + "\"" : value.toString();
                filterBuilder.append(key).append(" == ").append(fieldValue);
                hasFilter = true;
            }
        }

        // 默认返回所有字段
        var outputFields = Collections.singletonList("*");
        var topK = DEFAULT_TOP_K;

        // 构建并执行搜索请求
        var results = new ArrayList<T>();
        
        if (vectors.isEmpty()) {
            // 当 vectors 为空时，退化为 Query API
            var queryReq = io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(table)
                    .filter(hasFilter ? filterBuilder.toString() : null)
                    .outputFields(outputFields)
                    .build();
            
            var queryResp = client.query(queryReq);
            var queryResults = queryResp.getQueryResults();
            
            for (var result : queryResults) {
                var resultNode = JSONUtil.convert(result.getEntity());
                var item = JSONUtil.convert(resultNode, entityClass);
                results.add(item);
            }
        } else {
            // 使用 Search API 进行向量搜索
            var searchReq = io.milvus.v2.service.vector.request.SearchReq.builder()
                    .collectionName(table)
                    .data(vectors)
                    .filter(hasFilter ? filterBuilder.toString() : null)
                    .topK(topK)
                    .outputFields(outputFields)
                    .build();
            
            var searchResp = client.search(searchReq);
            var searchResults = searchResp.getSearchResults();
            
            if (!searchResults.isEmpty()) {
                for (var result : searchResults.get(0)) {
                    var entity = result.getEntity();
                    entity.remove("$meta");
                    var resultNode = JSONUtil.convert(entity);
                    var item = JSONUtil.convert(resultNode, entityClass);
                    results.add(item);
                }
            }
        }

        log.debug("mixedSearch: Retrieved {} results", results.size());
        return results;
    }


}