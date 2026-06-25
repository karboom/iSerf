package me.karboom.java.iSerf.rag.store;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import lombok.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MilvusStore 测试类
 */
public class MilvusStoreTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestEntity {
        public String id;
        public String name;
        public Integer value;
    }

    private MilvusClientV2 milvusClient;
    private MilvusStore<TestEntity> milvusStore;

    @BeforeEach
    void setUp() {
        var uri = System.getenv("MILVUS_URI");
        if (uri == null || uri.isEmpty()) {
            uri = "http://localhost:19530";
        }
        milvusClient = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
        milvusStore = new MilvusStore<>(milvusClient, "test_entity");
    }

    @AfterEach
    void tearDown() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    @Test
    @Timeout(30)
    void testCreate() {
        var testData = List.of(
            TestEntity.builder().id("test1").name("Test1").value(100).build(),
            TestEntity.builder().id("test2").name("Test2").value(200).build()
        );

        var result = milvusStore.create(testData);

        assertNotNull(result);
        assertEquals(2, result.size());

        var ids = List.of("test1", "test2");
        var queried = milvusStore.getByIds(ids);

        assertNotNull(queried);
        assertEquals(2, queried.size());
        assertEquals("Test1", queried.get(0).getName());
        assertEquals(100, queried.get(0).getValue());
        assertEquals("Test2", queried.get(1).getName());
        assertEquals(200, queried.get(1).getValue());
    }

    @Test
    @Timeout(30)
    void testGetByIds() {
        var testData = List.of(
            TestEntity.builder().id("get1").name("GetTest1").value(111).build(),
            TestEntity.builder().id("get2").name("GetTest2").value(222).build()
        );

        milvusStore.create(testData);

        var ids = List.of("get1", "get2");
        var result = milvusStore.getByIds(ids);

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @Timeout(30)
    void testUpdateByIds() {
        var testData = List.of(
            TestEntity.builder().id("update1").name("UpdateTest1").value(444).build()
        );

        milvusStore.create(testData);

        var updateData = TestEntity.builder().id("update1").name("UpdatedName").value(555).build();
        var ids = List.of("update1");

        milvusStore.updateByIds(ids, updateData);

        var result = milvusStore.getById("update1");

        assertNotNull(result);
        assertEquals("UpdatedName", result.getName());
        assertEquals(555, result.getValue());
    }

    @Test
    @Timeout(30)
    void testDeleteByIds() {
        var testData = List.of(
            TestEntity.builder().id("delete1").name("DeleteTest1").value(888).build(),
            TestEntity.builder().id("delete2").name("DeleteTest2").value(999).build()
        );

        milvusStore.create(testData);

        var ids = List.of("delete1", "delete2");
        milvusStore.deleteByIds(ids);

        var result = milvusStore.getByIds(ids);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
