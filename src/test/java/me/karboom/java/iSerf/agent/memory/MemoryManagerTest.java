package me.karboom.java.iSerf.agent.memory;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    private MemoryManager memoryManager;
    private Agent testAgent;

    @BeforeEach
    void setUp() {
        var llmTest = new OpenAITest();
        var provider = new FixedLlmProvider(llmTest.getLlm());
        memoryManager = new MemoryManager("你是一个测试助手");
        var config = new AgentConfig();
        var metadata = new AgentMetadata();
        metadata.setId("test-agent");
        config.setMetadata(metadata);
        config.setPrompt("test prompt");
        config.setLlm(provider);
        testAgent = new Agent(config);
    }

    // region ========== 构造函数 & systemPrompt ==========

    @Test
    void testConstructorAndSystemPrompt() {
        assertEquals("你是一个测试助手", memoryManager.getSystemPrompt());

        var mm = new MemoryManager(null);
        assertNull(mm.getSystemPrompt());

        mm.setSystemPrompt("新提示词");
        assertEquals("新提示词", mm.getSystemPrompt());
    }

    // endregion

    // region ========== 基础 CRUD ==========

    @Test
    void testAddAndSize() {
        assertEquals(0, memoryManager.size());
        assertTrue(memoryManager.isEmpty());

        memoryManager.add(AgentMessage.builder().text("hello").build());
        assertEquals(1, memoryManager.size());
        assertFalse(memoryManager.isEmpty());

        memoryManager.add(AgentMessage.builder().text("world").build());
        assertEquals(2, memoryManager.size());
    }

    @Test
    void testAddAll() {
        var list = List.of(
                AgentMessage.builder().text("a").build(),
                AgentMessage.builder().text("b").build(),
                AgentMessage.builder().text("c").build()
        );
        memoryManager.addAll(list);
        assertEquals(3, memoryManager.size());
    }

    @Test
    void testRemoveByEventId() {
        var m1 = AgentMessage.builder().text("keep").eventId("evt-1").build();
        var m2 = AgentMessage.builder().text("remove").eventId("evt-2").build();
        var m3 = AgentMessage.builder().text("keep2").eventId("evt-1").build();

        memoryManager.add(m1);
        memoryManager.add(m2);
        memoryManager.add(m3);

        memoryManager.removeByEventId("evt-2");
        assertEquals(2, memoryManager.size());

        memoryManager.removeByEventId("evt-1");
        assertEquals(0, memoryManager.size());
    }

    // endregion

    // region ========== 读取 ==========

    @Test
    void testGetMessagesReturnsDefensiveCopy() {
        memoryManager.add(AgentMessage.builder().text("original").build());

        var copy = memoryManager.getMessages();
        copy.add(AgentMessage.builder().text("added-later").build());

        assertEquals(1, memoryManager.size(), "防御性副本修改不应影响原列表");
    }

    @Test
    void testGetMessagesRawReturnsActualReference() {
        memoryManager.add(AgentMessage.builder().text("msg").build());

        var raw = memoryManager.getMessagesRaw();
        assertEquals(1, raw.size());

        raw.add(AgentMessage.builder().text("extra").build());
        assertEquals(2, memoryManager.size(), "原始引用修改应反映到内部列表");
    }

    @Test
    void testGetActiveMessagesFiltersForgotten() {
        memoryManager.add(AgentMessage.builder().text("active1").isForgotten(0).build());
        memoryManager.add(AgentMessage.builder().text("forgotten").isForgotten(1).build());
        memoryManager.add(AgentMessage.builder().text("active2").isForgotten(0).build());
        memoryManager.add(AgentMessage.builder().text("nullForgotten").isForgotten(null).build());

        var active = memoryManager.getActiveMessages();
        assertEquals(3, active.size());
        assertTrue(active.stream().allMatch(m ->
                m.getIsForgotten() == null || m.getIsForgotten() == 0));
    }

    @Test
    void testGetMessagesForLLMWithSystemPrompt() {
        memoryManager.add(AgentMessage.builder().text("user msg").isForgotten(0).role(AgentMessage.ROLE.USER).build());

        var llmMessages = memoryManager.getMessagesForLLM();

        assertEquals(2, llmMessages.size());
        assertEquals(AgentMessage.ROLE.SYSTEM, llmMessages.get(0).getRole());
        assertEquals("你是一个测试助手", llmMessages.get(0).getText());
        assertEquals("user msg", llmMessages.get(1).getText());
    }

    @Test
    void testGetMessagesForLLMWithoutSystemPrompt() {
        memoryManager.setSystemPrompt(null);
        memoryManager.add(AgentMessage.builder().text("user msg").isForgotten(0).build());

        var llmMessages = memoryManager.getMessagesForLLM();

        assertEquals(1, llmMessages.size());
        assertEquals("user msg", llmMessages.get(0).getText());
    }

    @Test
    void testGetMessagesForLLMEmptySystemPrompt() {
        memoryManager.setSystemPrompt("");
        memoryManager.add(AgentMessage.builder().text("user msg").isForgotten(0).build());

        var llmMessages = memoryManager.getMessagesForLLM();

        assertEquals(1, llmMessages.size());
    }

    // endregion

    // region ========== organizeMemory ==========

    @Test
    void testOrganizeMemoryEmpty() {
        var mm = new MemoryManager("prompt");
        mm.organizeMemory(testAgent);
        assertEquals(0, mm.size());
    }

    @Test
    @Timeout(30)
    void testOrganizeMemory() {
        // 添加多条消息
        for (int i = 0; i < 10; i++) {
            memoryManager.add(AgentMessage.builder()
                    .text("测试消息内容 " + i)
                    .role(AgentMessage.ROLE.USER)
                    .isForgotten(0)
                    .build());
            memoryManager.add(AgentMessage.builder()
                    .text("回复内容 " + i)
                    .role(AgentMessage.ROLE.ASSISTANT)
                    .isForgotten(0)
                    .build());
        }

        var sizeBefore = memoryManager.size();
        memoryManager.organizeMemory(testAgent);

        // 压缩后应有更多的消息（原消息 + 1条摘要）
        assertEquals(sizeBefore + 1, memoryManager.size());

        // 除最后一条摘要外，其他应被标记为遗忘
        var messages = memoryManager.getMessagesRaw();
        var forgottenCount = messages.stream()
                .limit(messages.size() - 1)
                .filter(m -> m.getIsForgotten() != null && m.getIsForgotten() == 1)
                .count();
        assertEquals(sizeBefore, forgottenCount, "除摘要外所有消息应标记为遗忘");

        // 摘要不应被遗忘
        var lastMsg = messages.get(messages.size() - 1);
        assertEquals(0, (int) lastMsg.getIsForgotten());
        assertEquals(AgentMessage.ROLE.ASSISTANT, lastMsg.getRole());
    }

    // endregion

    // region ========== checkAndOrganize ==========

    @Test
    void testCheckAndOrganizeBelowThreshold() {
        var msg = AgentMessage.builder()
                .text("hello")
                .usage(AgentMessage.Usage.builder().promptTotal(100).build())
                .build();
        memoryManager.add(msg);

        var sizeBefore = memoryManager.size();
        memoryManager.checkAndOrganize(testAgent);
        assertEquals(sizeBefore, memoryManager.size(), "低于阈值不应触发压缩");
    }

    @Test
    @Timeout(30)
    void testCheckAndOrganizeAboveThreshold() {
        var msg = AgentMessage.builder()
                .text("large message")
                .usage(AgentMessage.Usage.builder().promptTotal(160000).build())
                .isForgotten(0)
                .build();
        memoryManager.add(msg);

        var sizeBefore = memoryManager.size();
        memoryManager.checkAndOrganize(testAgent);

        assertEquals(sizeBefore + 1, memoryManager.size());
    }

    @Test
    void testCheckAndOrganizeNoMessages() {
        memoryManager.checkAndOrganize(testAgent);
        assertEquals(0, memoryManager.size());
    }

    // endregion

    // region ========== getMessagesForLLM 边界 ==========

    @Test
    void testGetMessagesForLLMSkipsForgotten() {
        memoryManager.add(AgentMessage.builder().text("visible").isForgotten(0).role(AgentMessage.ROLE.USER).build());
        memoryManager.add(AgentMessage.builder().text("hidden").isForgotten(1).role(AgentMessage.ROLE.USER).build());

        var llmMessages = memoryManager.getMessagesForLLM();

        // system prompt + 1 visible
        assertEquals(2, llmMessages.size());
        assertEquals(AgentMessage.ROLE.SYSTEM, llmMessages.get(0).getRole());
        assertTrue(llmMessages.stream().anyMatch(m -> "visible".equals(m.getText())));
        assertTrue(llmMessages.stream().noneMatch(m -> "hidden".equals(m.getText())));
    }

    // endregion

    // region ========== checkAndOrganize 边界 ==========

    @Test
    void testCheckAndOrganizeWithNullUsage() {
        var msg = AgentMessage.builder()
                .text("no usage field")
                .isForgotten(0)
                .build();
        memoryManager.add(msg);

        var sizeBefore = memoryManager.size();
        memoryManager.checkAndOrganize(testAgent);
        assertEquals(sizeBefore, memoryManager.size(), "null usage 不应触发压缩");
    }

    // endregion
}
