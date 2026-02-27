package me.karboom.java.iSerf.server.metaData;

import me.karboom.java.iSerf.util.DataUtil;
import org.junit.jupiter.api.Test;

public class PerformanceTest {
    /**
     * 根据 IMetaData，创建 10W 个 AS:flakeId - flakeId 和 10W 个 AN:flakeId - [ flakeId * 20个]，查看内存占用大小
     */
    @Test
    void testRedisMemorySize() {
        var redis = new RedisSingle("redis://localhost:6379");
        var count = 100_000;
        var createdKeys = new java.util.ArrayList<String>();

        var beforeMemory = redis.commands.info("memory").lines()
                .filter(line -> line.contains("used_memory_human"))
                .map(line -> line.split(":")[1].trim())
                .findFirst()
                .orElse("unknown");

        System.out.println("testRedisMemorySize 创建前 Redis 内存占用：%s".formatted(beforeMemory));

        var startTime = System.currentTimeMillis();

        for (var i = 0; i < count; i++) {
            var flakeId = DataUtil.getFlakeId();
            var asKey = "AS:%s".formatted(flakeId);
            createdKeys.add(asKey);
            redis.setAgentStay(flakeId, flakeId);

            for (var j = 0; j < 20; j++) {
                var subscribeNodeId = DataUtil.getFlakeId();
                redis.addAgentSubscribeNode(flakeId, subscribeNodeId);
            }

            var anKey = "AN:%s".formatted(flakeId);
            createdKeys.add(anKey);

            if (i % 10000 == 0) {
                System.out.println("testRedisMemorySize 已创建 %s 条记录".formatted(i));
            }
        }

        var endTime = System.currentTimeMillis();

        var afterMemory = redis.commands.info("memory").lines()
                .filter(line -> line.contains("used_memory_human"))
                .map(line -> line.split(":")[1].trim())
                .findFirst()
                .orElse("unknown");

        System.out.println("testRedisMemorySize 创建后 Redis 内存占用：%s".formatted(afterMemory));
        System.out.println("testRedisMemorySize 创建完成，耗时：%s ms".formatted(endTime - startTime));
        System.out.println("testRedisMemorySize 共创建 %s 个 key".formatted(createdKeys.size()));

        for (var key : createdKeys) {
            redis.commands.del(key);
        }

        var cleanedMemory = redis.commands.info("memory").lines()
                .filter(line -> line.contains("used_memory_human"))
                .map(line -> line.split(":")[1].trim())
                .findFirst()
                .orElse("unknown");

        System.out.println("testRedisMemorySize 清理后 Redis 内存占用：%s".formatted(cleanedMemory));
    }
}