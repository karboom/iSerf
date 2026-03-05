package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.memory.Item;
import org.junit.jupiter.api.Test;
import reactor.util.function.Tuple2;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NfsPersistence 测试类
 */
class NfsPersistenceTest {

    @Test
    void testSaveAndLoad() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var orgId = "test-org-" + UUID.randomUUID();
            var userId = "test-user-" + UUID.randomUUID();
            var agentId = "test-agent-" + UUID.randomUUID();

            // 测试加载不存在的文件
            var emptyResult = persistence.load(orgId, userId, agentId);
            assertNotNull(emptyResult);
            assertTrue(emptyResult.getT1().isEmpty(), "不存在的文件应返回空记忆列表");
            assertTrue(emptyResult.getT2().isEmpty(), "不存在的文件应返回空事件列表");

            // 准备测试数据
            var toolCall = Item.ToolCall.builder()
                    .id("call-001")
                    .name("getWeather")
                    .arguments(new java.util.HashMap<>() {{
                        put("location", "北京");
                    }})
                    .build();

            var memories = List.of(
                    Item.builder()
                            .id(UUID.randomUUID().toString())
                            .role(Item.ROLE.USER)
                            .type(Item.TYPE.TEXT)
                            .text("你好")
                            .build(),
                    Item.builder()
                            .id(UUID.randomUUID().toString())
                            .role(Item.ROLE.ASSISTANT)
                            .type(Item.TYPE.TEXT)
                            .text("你好，有什么可以帮助你的吗？")
                            .build(),
                    Item.builder()
                            .id(UUID.randomUUID().toString())
                            .role(Item.ROLE.ASSISTANT)
                            .type(Item.TYPE.TOOL_CALLS)
                            .toolCalls(List.of(toolCall))
                            .build()
            );

            var events = List.of(
                    Event.builder()
                            .type(Event.Type.MESSAGE)
                            .priority(1)
                            .build(),
                    Event.builder()
                            .type(Event.Type.ORGANIZE_MEMORY)
                            .priority(2)
                            .build()
            );

            // 保存数据
            persistence.save(orgId, userId, agentId, events, memories);
            Thread.sleep(100);

            // 加载数据
            var result = persistence.load(orgId, userId, agentId);

            // 验证结果
            assertNotNull(result);
            assertEquals(3, result.getT1().size(), "应加载 3 条记忆");
            assertEquals(2, result.getT2().size(), "应加载 2 个事件");

            // 验证记忆内容
            var loadedMemories = result.getT1();
            assertEquals("你好", loadedMemories.get(0).getText());
            assertEquals("你好，有什么可以帮助你的吗？", loadedMemories.get(1).getText());
            assertNotNull(loadedMemories.get(2).getToolCalls());
            assertEquals("getWeather", loadedMemories.get(2).getToolCalls().get(0).getName());

            // 验证事件类型
            var loadedEvents = result.getT2();
            assertEquals(Event.Type.MESSAGE, loadedEvents.get(0).getType());
            assertEquals(Event.Type.ORGANIZE_MEMORY, loadedEvents.get(1).getType());

            // 验证文件已创建
            var dir = "%s/%s/%s".formatted(baseDir, orgId, userId);
            var fileName = "%s/%s.cbor".formatted(dir, agentId);
            assertTrue(Files.exists(Paths.get(fileName)), "CBOR 文件应已创建");

            // 测试覆盖写入
            var memories2 = List.of(
                    Item.builder()
                            .id(UUID.randomUUID().toString())
                            .role(Item.ROLE.USER)
                            .type(Item.TYPE.TEXT)
                            .text("第二条消息")
                            .build()
            );
            persistence.save(orgId, userId, agentId, List.of(), memories2);
            Thread.sleep(100);

            var overwrittenResult = persistence.load(orgId, userId, agentId);
            assertEquals(1, overwrittenResult.getT1().size(), "应只加载 1 条记忆");
            assertEquals("第二条消息", overwrittenResult.getT1().get(0).getText(), "应为第二次保存的消息");
        });
    }
}
