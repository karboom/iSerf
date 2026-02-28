package me.karboom.java.iSerf.rag.vectorStore;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusVectorStore 测试类
 */
public class MilvusVectorStoreTest {

    private MilvusClientV2 client;
    private MilvusVectorStore<TestEntity> vectorStore;
    private static final String COLLECTION_NAME = "test_collection";

    /**
     * 测试实体类
     */
    public static class TestEntity {
        public String id;
        public String name;
        public List<Float> vector;

        public TestEntity() {}

        public TestEntity(String id, String name, List<Float> vector) {
            this.id = id;
            this.name = name;
            this.vector = vector;
        }
    }

    @BeforeEach
    void setUp() {
        var uri = System.getenv("MILVUS_URI");
        if (uri == null || uri.isEmpty()) {
            uri = "http://localhost:19530";
        }
        
        client = new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .build());
        
        vectorStore = new MilvusVectorStore<>(client, COLLECTION_NAME);
    }

    @Test
    void testCreate() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var testData = List.of(
                    new TestEntity("1", "test1", List.of(0.1f, 0.2f, 0.3f)),
                    new TestEntity("2", "test2", List.of(0.4f, 0.5f, 0.6f))
            );

            var result = vectorStore.create(testData);

            assertNotNull(result);
            assertEquals(2, result.size());
        });
    }

    @Test
    void testUpdateByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var ids = List.of("1");
            var updateData = new TestEntity("1", "updated_test1", List.of(0.15f, 0.25f, 0.35f));

            vectorStore.updateByIds(ids, updateData);
        });
    }

    @Test
    void testGetByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var ids = List.of("1", "2");

            var results = vectorStore.getByIds(ids);

            assertNotNull(results);
        });
    }

    @Test
    void testDeleteByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var ids = List.of("1", "2");

            vectorStore.deleteByIds(ids);
        });
    }

    @Test
    void testMixedSearch() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 纯向量搜索
            var query1 = new java.util.HashMap<String, Object>();
            query1.put("vector|ann", List.of(0.1f, 0.2f, 0.3f));
            var results1 = vectorStore.mixedSearch(query1);
            assertNotNull(results1);

            // 向量搜索 + 等于过滤
            var query2 = new java.util.HashMap<String, Object>();
            query2.put("vector|ann", List.of(0.1f, 0.2f, 0.3f));
            query2.put("name", "test1");
            var results2 = vectorStore.mixedSearch(query2);
            assertNotNull(results2);

            // 向量搜索 + in 过滤
            var query3 = new java.util.HashMap<String, Object>();
            query3.put("vector|ann", List.of(0.1f, 0.2f, 0.3f));
            query3.put("id|in", List.of("1", "2", "3"));
            var results3 = vectorStore.mixedSearch(query3);
            assertNotNull(results3);

            // 纯过滤（无向量搜索）
            var query4 = new java.util.HashMap<String, Object>();
            query4.put("name", "test1");
            var results4 = vectorStore.mixedSearch(query4);
            assertNotNull(results4);

            // 多条件过滤
            var query5 = new java.util.HashMap<String, Object>();
            query5.put("name", "test1");
            query5.put("id|in", List.of("1", "2", "3"));
            var results5 = vectorStore.mixedSearch(query5);
            assertNotNull(results5);
        });
    }
}