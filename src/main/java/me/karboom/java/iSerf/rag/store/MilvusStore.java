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

/**
 * 统一的 Milvus Store 实现
 * 支持结构化数据 CRUD 和向量混合搜索
 */
@Slf4j
public class MilvusStore<T> implements IStore<T> {

    private final MilvusClientV2 client;
    private final String collectionName;
    private final Class<T> entityClass;
    private final List<String> vectorFields;

    private static final String FIELD_ID = "id";
    private static final int DEFAULT_TOP_K = 10;

    public MilvusStore(MilvusClientV2 client, String collectionName) {
        this.client = client;
        this.collectionName = collectionName;

        var superClass = (ParameterizedType) getClass().getGenericSuperclass();
        this.entityClass = (Class<T>) superClass.getActualTypeArguments()[0];
        this.vectorFields = findVectorFields(entityClass);

        log.debug(" MilvusStore initialized: collectionName={}, entityClass={}, vectorFields={}",
                collectionName, entityClass.getSimpleName(), vectorFields);
    }

    // region create
    @Override
    public List<T> create(List<T> data) {
        log.debug(" create inserting {} entities", data.size());

        var dataList = data.stream()
                .map(item -> new Gson().toJsonTree(item).getAsJsonObject())
                .collect(Collectors.toList());

        var insertRequest = io.milvus.v2.service.vector.request.InsertReq.builder()
                .collectionName(collectionName)
                .data(dataList)
                .build();

        var insertResponse = client.insert(insertRequest);
        log.debug(" create inserted {} entities", insertResponse.getInsertCnt());

        return data;
    }
    // endregion

    // region update
    @Override
    public void updateByIds(List<String> ids, T data) {
        log.debug(" updateByIds updating {} entities with ids: {}", ids.size(), ids);

        var dataNode = new Gson().toJsonTree(data).getAsJsonObject();

        var upsertRequest = io.milvus.v2.service.vector.request.UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(dataNode))
                .build();

        var upsertResponse = client.upsert(upsertRequest);
        log.debug(" updateByIds upserted {} entities", upsertResponse.getUpsertCnt());
    }
    // endregion

    // region get
    @Override
    public List<T> getByIds(List<String> ids) {
        if (ids.isEmpty()) {
            log.debug(" getByIds ids is empty, returning all entities");
            var queryRequest = io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collectionName)
                    .outputFields(Collections.singletonList("*"))
                    .build();

            var queryResponse = client.query(queryRequest);
            return parseQueryResults(queryResponse.getQueryResults());
        }

        log.debug(" getByIds getting {} entities with ids: {}", ids.size(), ids);

        var filter = FIELD_ID + " in " + JSONUtil.stringify(
                ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"))
        );

        var queryRequest = io.milvus.v2.service.vector.request.QueryReq.builder()
                .collectionName(collectionName)
                .filter(filter)
                .outputFields(Collections.singletonList("*"))
                .build();

        var queryResponse = client.query(queryRequest);
        var results = parseQueryResults(queryResponse.getQueryResults());

        log.debug(" getByIds retrieved {} entities", results.size());
        return results;
    }
    // endregion

    // region delete
    @Override
    public void deleteByIds(List<String> ids) {
        log.debug(" deleteByIds deleting {} entities with ids: {}", ids.size(), ids);

        var deleteRequest = io.milvus.v2.service.vector.request.DeleteReq.builder()
                .collectionName(collectionName)
                .ids(ids.stream().map(Long::parseLong).collect(Collectors.toList()))
                .build();

        var deleteResponse = client.delete(deleteRequest);
        log.debug(" deleteByIds deleted {} entities", deleteResponse.getDeleteCnt());
    }
    // endregion

    // region mixedSearch
    @Override
    public List<T> mixedSearch(Map<String, Object> query) {
        log.debug(" mixedSearch starting mixed search with query: {}", query);

        var vectors = new ArrayList<BaseVector>();
        var filterBuilder = new StringBuilder();
        var hasFilter = false;

        for (var entry : query.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();

            if (key.endsWith("|ann")) {
                var floatArray = ((List<?>) value).stream()
                        .mapToDouble(v -> ((Number) v).floatValue())
                        .toArray();
                var floatArr = new float[floatArray.length];
                for (int i = 0; i < floatArray.length; i++) {
                    floatArr[i] = (float) floatArray[i];
                }
                vectors.add(new FloatVec(floatArr));
            } else if (key.endsWith("|in")) {
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
                if (hasFilter) {
                    filterBuilder.append(" and ");
                }
                var fieldValue = value instanceof String ? "\"" + value + "\"" : value.toString();
                filterBuilder.append(key).append(" == ").append(fieldValue);
                hasFilter = true;
            }
        }

        var outputFields = Collections.singletonList("*");
        var results = new ArrayList<T>();

        if (vectors.isEmpty()) {
            var queryReq = io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(hasFilter ? filterBuilder.toString() : null)
                    .outputFields(outputFields)
                    .build();

            var queryResp = client.query(queryReq);
            results.addAll(parseQueryResults(queryResp.getQueryResults()));
        } else {
            var searchReq = io.milvus.v2.service.vector.request.SearchReq.builder()
                    .collectionName(collectionName)
                    .data(vectors)
                    .filter(hasFilter ? filterBuilder.toString() : null)
                    .topK(DEFAULT_TOP_K)
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

        log.debug(" mixedSearch retrieved {} results", results.size());
        return results;
    }
    // endregion

    // region private methods
    private List<String> findVectorFields(Class<T> clazz) {
        var fields = new ArrayList<String>();
        for (Field field : clazz.getDeclaredFields()) {
            var genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                var rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?> classType && List.class.isAssignableFrom(classType)) {
                    var typeArguments = parameterizedType.getActualTypeArguments();
                    if (typeArguments.length == 1 && typeArguments[0] instanceof Class<?> argClass) {
                        if (Float.class.isAssignableFrom(argClass) || float.class.isAssignableFrom(argClass)) {
                            fields.add(field.getName());
                        }
                    }
                }
            }
        }
        log.debug(" findVectorFields found {} vector fields: {}", fields.size(), fields);
        return fields;
    }

    private List<T> parseQueryResults(List<?> queryResults) {
        var results = new ArrayList<T>();
        for (var result : queryResults) {
            var resultNode = JSONUtil.convert(result);
            var item = JSONUtil.convert(resultNode, entityClass);
            results.add(item);
        }
        return results;
    }
    // endregion
}
