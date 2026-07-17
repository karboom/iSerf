package me.karboom.java.iSerf.llm.text;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic Claude Messages API 测试类 — 正交法用例设计
 *
 * 因素-水平:
 *   A. messages: A1-单轮text / A2-多轮 / A3-image
 *   B. outputFormat: B1-null / B2-Class
 *   C. tools: C1-null / C2-List
 *   D. model: D1-text (Anthropic 不支持 video/audio)
 *
 * 正交表:
 *   TextModel:    T1(A1,B1,C1,D1) T2(A2,B2,C2,D1) T3(A1,B2,C1,D1) T4(A2,B1,C2,D1)
 *   Multimodal:   M1(A3,B1,C1,D1)
 *   CrossValid:   X1(A3,B2,C1,D1) X2(A2+toolResult,B1,C2,D1)
 *   Exception:    E1(空messages) E2(无效apiKey)
 */
@Slf4j
@Timeout(60)
public class AnthropicTest {

    private Anthropic llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    private Anthropic getLlm() {
        return this.getLlm("qwen3.6-flash");
    }

    private Anthropic getLlm(String model) {
        var apiKey = System.getenv("ANTHROPIC_API_KEY");
        var url = System.getenv("ANTHROPIC_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/apps/anthropic";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 4096);
        llmConfig.put("top_p", 0.9);

        return new Anthropic(model, llmConfig, apiKey, url, 1);
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
                            if (choice.getToolCall() != null) {
                                collector.toolCalls.addAll(choice.getToolCall());
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
                    log.debug("<collect> completed | content.length={}, toolCalls={}", collector.content.length(), collector.toolCalls.size());
                    collector.latch.countDown();
                }
        );
        return collector;
    }

    static class StreamCollector {
        StringBuilder content = new StringBuilder();
        ArrayList<Output.ToolCall> toolCalls = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        boolean await(long seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }
    }

    // region TextModel — T1~T4

    @Nested
    class TextModel {

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

        @Test
        @SneakyThrows
        void testStructuredOutputWithTools() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("柏林天气如何")
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true, null);
            var tool1 = Tool.<Map>builder().name("query_weather").description("查询天气").parameters(List.of(p1)).build();

            var collector = collect(llm.send(messages, WeatherResponse.class, List.of(tool1)));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0 || !collector.toolCalls.isEmpty());
            log.debug("<testStructuredOutputWithTools> result | content={}, toolCalls={}", collector.content, collector.toolCalls.size());
        }

        @Test
        @SneakyThrows
        void testStructuredOutput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is the weather in Paris? Give me a random temperature.")
                    .build());

            var collector = collect(llm.send(messages, WeatherResponse.class, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            // Anthropic structured output returns via tool_call
            assertTrue(collector.content.length() > 0 || !collector.toolCalls.isEmpty());
            log.debug("<testStructuredOutput> result | content={}, toolCalls={}", collector.content, collector.toolCalls.size());
        }

        @Test
        @SneakyThrows
        void testMultiTurnWithTools() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("You are a helpful assistant. Use the weather query tool when asked about weather.")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is the weather in Berlin and Beijing?")
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true, null);
            var tool1 = Tool.<Map>builder()
                    .name("query_weather")
                    .description("当你需要查询天气，使用这个工具")
                    .parameters(List.of(p1))
                    .build();

            var collector = collect(llm.send(messages, null, List.of(tool1)));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertFalse(collector.toolCalls.isEmpty());
            log.debug("<testMultiTurnWithTools> result | toolCalls={}", collector.toolCalls.size());
        }
    }

    // endregion

    // region Multimodal — M1

    @Nested
    class Multimodal {

        @Test
        @SneakyThrows
        void testImageInput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.IMAGE)
                    .files(List.of("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_PNG_500kB.png"))
                    .text("What color is this image?")
                    .build());

            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testImageInput> result | content={}", collector.content);
        }
    }

    // endregion

    // region CrossValidation — X1~X2

    @Nested
    class CrossValidation {

        @Test
        @SneakyThrows
        void testImageWithStructuredOutput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.IMAGE)
                    .files(List.of("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_PNG_500kB.png"))
                    .text("描述这张图片，输出颜色名称和主要物体")
                    .build());

            var collector = collect(llm.send(messages, ImageDescResponse.class, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0 || !collector.toolCalls.isEmpty());
            log.debug("<testImageWithStructuredOutput> result | content={}, toolCalls={}", collector.content, collector.toolCalls.size());
        }

        @Test
        @SneakyThrows
        void testToolCallChain() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("柏林天气如何？")
                    .build());

            var toolCall = AgentMessage.ToolCall.builder()
                    .id("call_001")
                    .name("query_weather")
                    .arguments(new HashMap<>(Map.of("location", "柏林")))
                    .build();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.ASSISTANT)
                    .type(AgentMessage.TYPE.TOOL_CALLS)
                    .toolCalls(List.of(toolCall))
                    .build());

            var toolResult = AgentMessage.ToolCall.builder()
                    .id("call_001")
                    .name("query_weather")
                    .result(CallResult.builder().llm("柏林当前气温15°C，多云").build())
                    .build();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.TOOL)
                    .type(AgentMessage.TYPE.TEXT)
                    .toolCalls(List.of(toolResult))
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true, null);
            var tool1 = Tool.<Map>builder().name("query_weather").description("查询天气").parameters(List.of(p1)).build();

            var collector = collect(llm.send(messages, null, List.of(tool1)));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testToolCallChain> result | content={}", collector.content);
        }
    }

    static class ImageDescResponse {
        public String color;
        public String object;
    }

    // endregion

    // region ExceptionCases — E1~E2

    @Nested
    class ExceptionCases {

        @Test
        @SneakyThrows
        void testEmptyMessages() {
            var messages = new ArrayList<AgentMessage>();
            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNotNull(collector.error.get(), "空 messages 应触发错误");
            log.debug("<testEmptyMessages> error | error={}", collector.error.get().getMessage());
        }

        @Test
        @SneakyThrows
        void testInvalidApiKey() {
            var invalidLlm = new Anthropic(
                    "qwen3.6-flash",
                    new HashMap<>(Map.of("max_tokens", 4096)),
                    "invalid-api-key",
                    "https://dashscope.aliyuncs.com/apps/anthropic",
                    1
            );

            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("hello")
                    .build());

            var collector = collect(invalidLlm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNotNull(collector.error.get(), "无效 API Key 应触发错误");
            log.debug("<testInvalidApiKey> error | error={}", collector.error.get().getMessage());
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
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("What is 1+1?")
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

    // region Batch Tests (Unsupported)

    @Nested
    class Batch {

        @Test
        void testBatchUnsupported() {
            var messageBatch = new ArrayList<List<AgentMessage>>();
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("Hello")
                    .build());
            messageBatch.add(messages);

            assertThrows(RuntimeException.class, () -> llm.batch(messageBatch, null));
        }

        @Test
        void testTaskStatusUnsupported() {
            assertThrows(RuntimeException.class, () -> llm.taskStatus("test-id"));
        }

        @Test
        void testTaskResultUnsupported() {
            var task = BatchTaskInfo.builder().id("test-id").build();
            assertThrows(RuntimeException.class, () -> llm.taskResult(task));
        }
    }

    // endregion

    // region BuildToolsJson Tests

    @Nested
    class BuildToolsJson {

        @Test
        void testBuildToolsJson() {
            var subParam1 = new Tool.Parameter("street", "string", "街道地址", true, null);
            var subParam2 = new Tool.Parameter("city", "string", "城市名称", true, null);
            var subParam3 = new Tool.Parameter("zipcode", "string", "邮政编码", false, null);

            var addressParam = new Tool.Parameter("address", "object", "详细地址信息", true, List.of(subParam1, subParam2, subParam3));
            var tool = Tool.<Map>builder()
                    .name("search_location")
                    .description("根据地址搜索地理位置")
                    .parameters(List.of(addressParam))
                    .build();

            var toolsJson = llm.buildToolsJson(List.of(tool));

            assertNotNull(toolsJson);
            assertEquals(1, toolsJson.size());

            var toolNode = toolsJson.get(0);
            assertEquals("search_location", toolNode.path("name").asText());
            assertEquals("根据地址搜索地理位置", toolNode.path("description").asText());

            var inputSchema = toolNode.path("input_schema");
            var properties = inputSchema.path("properties");
            var addressProperty = properties.path("address");
            assertEquals("object", addressProperty.path("type").asText());
            assertEquals("详细地址信息", addressProperty.path("description").asText());

            var addressProperties = addressProperty.path("properties");
            assertEquals("string", addressProperties.path("street").path("type").asText());
            assertEquals("string", addressProperties.path("city").path("type").asText());
            assertEquals("string", addressProperties.path("zipcode").path("type").asText());
        }
    }

    // endregion

    // region Thinking (补充用例)

    @Nested
    class Thinking {

        @Test
        @SneakyThrows
        void testThinking() {
            var llmConfig = new HashMap<String, Object>();
            llmConfig.put("max_tokens", 4096);
            llmConfig.put("thinking", true);

            var thinkingLlm = new Anthropic(
                    "claude-3-5-haiku-20241022",
                    llmConfig,
                    System.getenv("ANTHROPIC_API_KEY"),
                    "https://api.anthropic.com/v1",
                    1
            );

            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .text("Solve this math problem: If a train travels 120 km in 2 hours, what is its average speed?")
                    .build());

            var collector = collect(thinkingLlm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testThinking> result | content={}", collector.content);
        }
    }

    // endregion
}
