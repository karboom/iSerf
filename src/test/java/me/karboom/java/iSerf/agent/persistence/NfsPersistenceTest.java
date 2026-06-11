package me.karboom.java.iSerf.agent.persistence;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.Event;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.tool.CallCache;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NfsPersistence 测试类
 */
class NfsPersistenceTest {

    @Test
    void testLoadAndRemove() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            // 测试加载不存在的数据
            var emptyResult = persistence.load(metadata);
            assertNotNull(emptyResult);
            assertTrue(emptyResult.getMemories().isEmpty(), "不存在的数据应返回空记忆列表");
            assertTrue(emptyResult.getEvents().isEmpty(), "不存在的数据应返回空事件列表");
            assertTrue(emptyResult.getToolCalls().isEmpty(), "不存在的数据应返回空工具调用列表");
            assertTrue(emptyResult.getPlans().isEmpty(), "不存在的数据应返回空计划列表");

            // 验证目录不存在
            var agentDir = "%s/%s/%s/%s".formatted(baseDir, metadata.getOrgId(), metadata.getUserId(), metadata.getId());
            assertFalse(Files.exists(Paths.get(agentDir)), "目录不应存在");

            // 测试 remove 不存在的数据（不应抛出异常）
            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;
            persistence.remove(agent);
        });
    }

    @Test
    void testSyncMemory() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            // 创建 Agent 并添加记忆
            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            var message1 = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("你好")
                    .build();
            agent.getMemoryManager().add(message1);

            // 全量同步记忆
            persistence.syncMemory(agent);

            // 加载并验证
            var result = persistence.load(metadata);
            assertEquals(1, result.getMemories().size(), "应加载 1 条记忆");
            assertEquals("你好", result.getMemories().getFirst().getText());

            // 添加更多记忆并再次同步
            var message2 = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.ASSISTANT)
                    .type(Message.TYPE.TEXT)
                    .text("你好，有什么可以帮助你的吗？")
                    .build();
            agent.getMemoryManager().add(message2);
            persistence.syncMemory(agent);

            result = persistence.load(metadata);
            assertEquals(2, result.getMemories().size(), "应加载 2 条记忆");

            // 清理
            persistence.remove(agent);
        });
    }

    @Test
    void testAddMemory() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            // 添加第一条记忆并追加
            var message1 = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("第一条消息")
                    .build();
            agent.getMemoryManager().add(message1);
            persistence.addMemory(agent);

            // 添加第二条记忆并追加
            var message2 = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.ASSISTANT)
                    .type(Message.TYPE.TEXT)
                    .text("第二条消息")
                    .build();
            agent.getMemoryManager().add(message2);
            persistence.addMemory(agent);

            // 加载并验证
            var result = persistence.load(metadata);
            assertEquals(2, result.getMemories().size(), "应加载 2 条记忆");
            assertEquals("第一条消息", result.getMemories().get(0).getText());
            assertEquals("第二条消息", result.getMemories().get(1).getText());

            // 清理
            persistence.remove(agent);
        });
    }

    @Test
    void testSyncEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            // 同步事件（清空）
            persistence.syncEvent(agent);

            // 加载并验证
            var result = persistence.load(metadata);
            assertTrue(result.getEvents().isEmpty(), "事件应为空");

            // 清理
            persistence.remove(agent);
        });
    }

    @Test
    void testSyncToolCall() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            // 添加工具调用缓存
            var cache = CallCache.builder()
                    .callId("call-001")
                    .toolName("getWeather")
                    .params(new HashMap<>() {{
                        put("location", "北京");
                    }})
                    .build();
            agent.toolHandler.addCache(cache);

            // 全量同步
            persistence.syncToolCall(agent);

            // 加载并验证
            var result = persistence.load(metadata);
            assertEquals(1, result.getToolCalls().size(), "应加载 1 个工具调用缓存");
            assertEquals("getWeather", result.getToolCalls().getFirst().getToolName());

            // 清理
            persistence.remove(agent);
        });
    }

    @Test
    void testAddToolCall() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            // 添加第一个工具调用缓存
            var cache1 = CallCache.builder()
                    .callId("call-001")
                    .toolName("getWeather")
                    .params(new HashMap<>() {{
                        put("location", "北京");
                    }})
                    .build();
            agent.toolHandler.addCache(cache1);
            persistence.addToolCall(agent);

            // 添加第二个工具调用缓存
            var cache2 = CallCache.builder()
                    .callId("call-002")
                    .toolName("getTemperature")
                    .params(new HashMap<>() {{
                        put("city", "上海");
                    }})
                    .build();
            agent.toolHandler.addCache(cache2);
            persistence.addToolCall(agent);

            // 加载并验证
            var result = persistence.load(metadata);
            assertEquals(2, result.getToolCalls().size(), "应加载 2 个工具调用缓存");
            assertEquals("getWeather", result.getToolCalls().get(0).getToolName());
            assertEquals("getTemperature", result.getToolCalls().get(1).getToolName());

            // 清理
            persistence.remove(agent);
        });
    }

    @Test
    void testRemove() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var metadata = AgentMetadata.builder()
                    .orgId("test-org-" + UUID.randomUUID())
                    .userId("test-user-" + UUID.randomUUID())
                    .id("test-agent-" + UUID.randomUUID())
                    .build();

            var agent = new Agent(metadata.getId(), "test prompt", null, List.of());
            agent.metadata = metadata;

            // 添加一些数据
            agent.getMemoryManager().add(Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("测试消息")
                    .build());
            persistence.syncMemory(agent);

            // 验证数据存在
            var result = persistence.load(metadata);
            assertEquals(1, result.getMemories().size(), "应有 1 条记忆");

            // 删除数据
            persistence.remove(agent);

            // 验证数据已删除
            result = persistence.load(metadata);
            assertTrue(result.getMemories().isEmpty(), "记忆应为空");

            // 验证目录已删除
            var agentDir = "%s/%s/%s/%s".formatted(baseDir, metadata.getOrgId(), metadata.getUserId(), metadata.getId());
            assertFalse(Files.exists(Paths.get(agentDir)), "目录应已删除");
        });
    }
}
