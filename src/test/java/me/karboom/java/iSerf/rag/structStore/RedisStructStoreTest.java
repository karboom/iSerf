package me.karboom.java.iSerf.rag.structStore;

import io.lettuce.core.RedisClient;
import me.karboom.java.iSerf.rag.store.RedisStructStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import lombok.*;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisStructStore 测试类
 */
public class RedisStructStoreTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestEntity {
        public String id;
        public String name;
        public Integer value;
    }

    private RedisClient redisClient;
    private RedisStructStore<TestEntity> redisStructStore;
    private static final String KEY_PREFIX = "test:%s";

    @BeforeEach
    void setUp() {
        redisClient = RedisClient.create("redis://localhost");
        redisStructStore = new RedisStructStore<TestEntity>(redisClient, KEY_PREFIX){};
    }

    @AfterEach
    void tearDown() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void testCreate() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var testData = List.of(
                TestEntity.builder().id("test1").name("Test1").value(100).build(),
                TestEntity.builder().id("test2").name("Test2").value(200).build()
            );

            var result = redisStructStore.create(testData);

            assertNotNull(result);
            assertEquals(2, result.size());

            var ids = List.of("test1", "test2");
            var queried = redisStructStore.<TestEntity>getByIds(ids);

            assertNotNull(queried);
            assertEquals(2, queried.size());
            assertEquals("Test1", queried.get(0).getName());
            assertEquals(100, queried.get(0).getValue());
            assertEquals("Test2", queried.get(1).getName());
            assertEquals(200, queried.get(1).getValue());
        });
    }

    @Test
    void testGetByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var testData = List.of(
                TestEntity.builder().id("get1").name("GetTest1").value(111).build(),
                TestEntity.builder().id("get2").name("GetTest2").value(222).build()
            );

            redisStructStore.create(testData);

            var ids = List.of("get1", "get2");
            var result = redisStructStore.getByIds(ids);

            assertNotNull(result);
            assertEquals(2, result.size());
        });
    }

    @Test
    void testUpdateByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var testData = List.of(
                TestEntity.builder().id("update1").name("UpdateTest1").value(444).build()
            );

            redisStructStore.create(testData);

            var updateData = TestEntity.builder().id("update1").name("UpdatedName").value(555).build();
            var ids = List.of("update1");

            redisStructStore.updateByIds(ids, updateData);

            var result = redisStructStore.getById("update1");

            assertNotNull(result);
            assertEquals("UpdatedName", result.getName());
            assertEquals(555, result.getValue());
        });
    }

    @Test
    void testDeleteByIds() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var testData = List.of(
                TestEntity.builder().id("delete1").name("DeleteTest1").value(888).build(),
                TestEntity.builder().id("delete2").name("DeleteTest2").value(999).build()
            );

            redisStructStore.create(testData);

            var ids = List.of("delete1", "delete2");
            redisStructStore.deleteByIds(ids);

            var result = redisStructStore.getByIds(ids);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        });
    }

}