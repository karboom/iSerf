package me.karboom.java.iSerf.llm.text;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 测试类 — 正交法用例设计
 * 注意：这些测试需要本地运行的 Ollama 服务 (ollama serve)
 *
 * 因素-水平:
 *   A. messages: A1-单轮text / A2-多轮
 *   B. outputFormat: B1-null / B2-Class
 *   C. tools: 不支持
 *   D. model: D1-text (qwen2.5:7b)
 *
 * 正交表:
 *   TextModel:    T1(A1,B1) T3(A1,B2) T4'(A2,B1)
 *   Exception:    E1(空messages)
 *   Unsupported:  batch/taskStatus/taskResult
 */
@Slf4j
@Timeout(60)
public class OllamaTest {

    private Ollama llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    private Ollama getLlm() {
        return this.getLlm("qwen2.5:7b");
    }

    private Ollama getLlm(String model) {
        var apiKey = System.getenv("OLLAMA_API_KEY");
        var url = System.getenv("OLLAMA_URL");

        if (url == null || url.isEmpty()) {
            url = "http://localhost:11434";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);
        llmConfig.put("top_p", 0.9);

        return new Ollama(model, llmConfig, apiKey, url, 1);
    }

    static class WeatherResponse {
        public String location;
        public String weather;
        public Integer temperature;
    }

    private StreamCollector collect(Flux<Output> flux) {
        var collector = new StreamCollector();
        flux.subscribe(
                chunk -> {
                    log.debug("<collect> received chunk | choices={}", chunk.getChoices() != null ? chunk.getChoices().size() : 0);
                    if (chunk.getChoices() != null) {
                        for (var choice : chunk.getChoices()) {
                            if (choice.getText() != null) {
                                collector.content.append(choice.getText());
                            }
                        }
                    }
                },
                error -> {
                    log.error("<collect> error | error={}", error.getMessage());
                    collector.error.set(error);
                    collector.latch.countDown();
                },
                () -> {
                    log.debug("<collect> completed | content.length={}", collector.content.length());
                    collector.latch.countDown();
                }
        );
        return collector;
    }

    static class StreamCollector {
        StringBuilder content = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        boolean await(long seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }
    }

    // region TextModel — T1, T3, T4'

    @Nested
    class TextModel {

        /**
         * T1: A1-单轮text + B1-null
         * 基础对话
         */
        @Test
        @SneakyThrows
        void testBasicText() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is the capital of France?")
                    .build());

            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            assertTrue(collector.content.toString().toLowerCase().contains("paris"));
            log.debug("<testBasicText> result | content={}", collector.content);
        }

        /**
         * T3: A1-单轮text + B2-Class
         * 结构化输出 (Ollama 使用 format:json)
         */
        @Test
        @SneakyThrows
        void testStructuredOutput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("北京的气温是多少度？用 JSON 格式回答。")
                    .build());

            var collector = collect(llm.send(messages, WeatherResponse.class, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testStructuredOutput> result | content={}", collector.content);
        }

        /**
         * T4': A2-多轮 + B1-null
         * 多轮对话 (Ollama 不支持 tools)
         */
        @Test
        @SneakyThrows
        void testMultiTurn() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is the capital of France?")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.ASSISTANT)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("The capital of France is Paris.")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is its population?")
                    .build());

            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testMultiTurn> result | content={}", collector.content);
        }
    }

    // endregion

    // region ExceptionCases — E1

    @Nested
    class ExceptionCases {

        /**
         * E1: 空 messages
         */
        @Test
        @SneakyThrows
        void testEmptyMessages() {
            var messages = new ArrayList<AgentMessage>();
            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNotNull(collector.error.get(), "空 messages 应触发错误");
            log.debug("<testEmptyMessages> error | error={}", collector.error.get().getMessage());
        }
    }

    // endregion

    // region Query Tests

    @Nested
    class Query {

        @Test
        void testQuery() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("你是一个乐于助人的助手。")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("1+1 等于几？")
                    .build());

            var response = llm.query(messages, null);

            assertNotNull(response);
            assertNotNull(response.getChoices());
            assertFalse(response.getChoices().isEmpty());

            var choice = response.getChoices().getFirst();
            assertNotNull(choice.getText());
            assertTrue(choice.getText().length() > 0);

            log.debug("<testQuery> response | text={}", choice.getText());
        }
    }

    // endregion

    // region Unsupported Operations Tests

    @Nested
    class UnsupportedOperations {

        @Test
        void testBatchNotSupported() {
            var messages = new ArrayList<List<AgentMessage>>();
            var msg = new ArrayList<AgentMessage>();
            msg.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("Hello")
                    .build());
            messages.add(msg);

            assertThrows(UnsupportedOperationException.class, () -> llm.batch(messages, null));
        }

        @Test
        void testTaskStatusNotSupported() {
            assertThrows(UnsupportedOperationException.class, () -> llm.taskStatus("test-id"));
        }

        @Test
        void testTaskResultNotSupported() {
            var task = BatchTaskInfo.builder().id("test-id").build();
            assertThrows(UnsupportedOperationException.class, () -> llm.taskResult(task));
        }
    }

    // endregion
}
