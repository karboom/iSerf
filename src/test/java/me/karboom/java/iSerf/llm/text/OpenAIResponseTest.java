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
 * OpenAIResponse Responses API 测试类 — 正交法用例设计
 *
 * 因素-水平:
 *   A. messages: A1-单轮text / A2-多轮 / A3-image / A4-video / A5-audio
 *   B. outputFormat: B1-null / B2-Class
 *   C. tools: C1-null / C2-List
 *   D. model: D1-text / D2-vision / D3-omni
 *
 * 正交表:
 *   TextModel:    T1(A1,B1,C1,D1) T2(A2,B2,C2,D1) T3(A1,B2,C1,D1) T4(A2,B1,C2,D1)
 *   Multimodal:   M1(A3,B1,C1,D2) M2(A4,B1,C1,D3) M3(A5,B1,C1,D3)
 *   CrossValid:   X1(A3,B2,C1,D2) X2(A2+toolResult,B1,C2,D1)
 *   Exception:    E1(空messages) E2(无效apiKey) E3(超时)
 */
@Slf4j
@Timeout(60)
public class OpenAIResponseTest {

    private OpenAIResponse llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    private OpenAIResponse getLlm() {
        return this.getLlm("qwen-plus");
    }

    private OpenAIResponse getLlm(String model) {
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("top_p", 0.9);

        return new OpenAIResponse(model, llmConfig, apiKey, url, 1);
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
                    .text("1+1等于几？直接回答数字")
                    .build());

            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
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
            assertTrue(collector.content.length() > 0);
            assertTrue(collector.content.toString().contains("巴黎") || collector.content.toString().contains("Paris"));
            log.debug("<testStructuredOutput> result | content={}", collector.content);
        }

        @Test
        @SneakyThrows
        void testMultiTurnWithTools() {
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
                    .text("柏林天气如何，北京天气如何")
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true, null);
            var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true, null);
            var tool1 = Tool.<Map>builder().name("query").description("当你需要查询天气，使用这个工具").parameters(List.of(p1, p2)).build();

            var collector = collect(llm.send(messages, null, List.of(tool1)));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            log.debug("<testMultiTurnWithTools> result | content.length={}, toolCalls={}", collector.content.length(), collector.toolCalls.size());
        }
    }

    // endregion

    // region Multimodal — M1~M3

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
                    .text("这张图片是什么颜色的？")
                    .build());

            var collector = collect(getLlm("qwen3-vl-plus").send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testImageInput> result | content={}", collector.content);
        }

        @Test
        @SneakyThrows
        void testVideoInput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.VIDEO)
                    .video("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_MP4_480_1_5MG.mp4")
                    .fps("5")
                    .text("描述这个视频的内容")
                    .build());

            var collector = collect(getLlm("qwen3-omni-flash").send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testVideoInput> result | content={}", collector.content);
        }

        @Test
        @SneakyThrows
        void testAudioInput() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.AUDIO)
                    .audio("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_MP3_700KB.mp3")
                    .text("描述音频的内容")
                    .build());

            var collector = collect(getLlm("qwen3-omni-flash").send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testAudioInput> result | content={}", collector.content);
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

            var collector = collect(getLlm("qwen3-vl-plus").send(messages, ImageDescResponse.class, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testImageWithStructuredOutput> result | content={}", collector.content);
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

    // region ExceptionCases — E1~E3

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
            var invalidLlm = new OpenAIResponse(
                    "qwen-plus",
                    new HashMap<>(Map.of("temperature", 0.7)),
                    "invalid-api-key",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
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

        @Test
        @SneakyThrows
        void testNetworkTimeout() {
            var timeoutLlm = new OpenAIResponse(
                    "qwen-plus",
                    new HashMap<>(Map.of("temperature", 0.7)),
                    "test-key",
                    "http://10.255.255.1:1",
                    1
            );

            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("hello")
                    .build());

            var collector = collect(timeoutLlm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNotNull(collector.error.get(), "网络超时 应触发错误");
            log.debug("<testNetworkTimeout> error | error={}", collector.error.get().getMessage());
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
                    .text("1+1等于几？")
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

    // region Batch Tests

    @Nested
    class Batch {

        private List<List<AgentMessage>> createWeatherMessageBatch() {
            var messageBatch = new ArrayList<List<AgentMessage>>();

            var messages1 = new ArrayList<AgentMessage>();
            messages1.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("你是一个乐于助人的助手。")
                    .build());
            messages1.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("巴黎的天气怎么样？给出一个随机温度。")
                    .build());
            messageBatch.add(messages1);

            var messages2 = new ArrayList<AgentMessage>();
            messages2.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.SYSTEM)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("你是一个乐于助人的助手。")
                    .build());
            messages2.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .type(AgentMessage.TYPE.TEXT)
                    .text("北京的天气怎么样？给出一个随机温度。")
                    .build());
            messageBatch.add(messages2);

            return messageBatch;
        }

        @Test
        @SneakyThrows
        void testBatch() {
            var messageBatch = createWeatherMessageBatch();
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);

            assertNotNull(batchId);
            assertFalse(batchId.isEmpty());
            log.debug("<testBatch> batch created | batchId={}", batchId);
        }

        @Test
        @SneakyThrows
        void testTaskStatus() {
            var messageBatch = createWeatherMessageBatch();
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);
            var taskStatus = getLlm("batch-test-model").taskStatus(batchId);

            assertNotNull(taskStatus);
            assertNotNull(taskStatus.getId());
            assertNotNull(taskStatus.getStatus());

            assertTrue(
                    BatchTaskInfo.STATUS.DOING.equals(taskStatus.getStatus()) ||
                            BatchTaskInfo.STATUS.DONE.equals(taskStatus.getStatus()) ||
                            BatchTaskInfo.STATUS.ERROR.equals(taskStatus.getStatus()) ||
                            BatchTaskInfo.STATUS.EXPIRED.equals(taskStatus.getStatus()) ||
                            BatchTaskInfo.STATUS.CANCELLED.equals(taskStatus.getStatus())
            );
            log.debug("<testTaskStatus> task status | status={}", taskStatus);
        }

        @Test
        @SneakyThrows
        void testTaskResult() {
            var messageBatch = createWeatherMessageBatch();
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);

            BatchTaskInfo batchTaskInfo;
            int maxRetries = 12;
            int retryCount = 0;

            do {
                batchTaskInfo = getLlm("batch-test-model").taskStatus(batchId);
                if (BatchTaskInfo.STATUS.DONE.equals(batchTaskInfo.getStatus())) {
                    break;
                }
                Thread.sleep(5000);
                retryCount++;
            } while (retryCount < maxRetries);

            if (retryCount >= maxRetries) {
                throw new RuntimeException("Batch task did not complete within timeout period");
            }

            assertNotNull(batchTaskInfo);
            assertNotNull(batchTaskInfo.getSuccessResultId());

            var results = getLlm("batch-test-model").taskResult(batchTaskInfo);
            assertNotNull(results);
            assertFalse(results.isEmpty());
            assertEquals(2, results.size());

            log.debug("<testTaskResult> task results | results.size={}", results.size());
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
            var function = toolNode.path("function");
            var parameters = function.path("parameters");

            var properties = parameters.path("properties");
            var addressProperty = properties.path("address");
            assertEquals("object", addressProperty.path("type").asText());
            assertEquals("详细地址信息", addressProperty.path("description").asText());

            var addressProperties = addressProperty.path("properties");
            assertEquals("string", addressProperties.path("street").path("type").asText());
            assertEquals("string", addressProperties.path("city").path("type").asText());
            assertEquals("string", addressProperties.path("zipcode").path("type").asText());

            var addressRequired = addressProperty.path("required");
            assertTrue(addressRequired.isArray());
            assertEquals(2, addressRequired.size());
        }
    }

    // endregion

    // region Thinking (补充用例)

    @Nested
    class Thinking {

        @Test
        @SneakyThrows
        void testThinking() {
            var messages = new ArrayList<AgentMessage>();
            messages.add(AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER)
                    .text("弄一幅对联")
                    .build());

            var collector = collect(llm.send(messages, null, null));
            assertTrue(collector.await(30));
            assertNull(collector.error.get());
            assertTrue(collector.content.length() > 0);
            log.debug("<testThinking> result | content={}", collector.content);
        }
    }

    // endregion
}
