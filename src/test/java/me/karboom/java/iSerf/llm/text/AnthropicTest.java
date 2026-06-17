package me.karboom.java.iSerf.llm.text;

import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic Claude Messages API 测试类
 * 注意：这些测试需要真实的 API Key 才能运行
 */
public class AnthropicTest {

    private Anthropic llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    public Anthropic getLlm() {
        return this.getLlm("qwen3.6-flash");
    }

    public Anthropic getLlm(String model) {
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

    @Test
    @Timeout(30)
    void testBasicText() throws InterruptedException {
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

        var response = llm.send(messages, null, null);

        var content = new StringBuilder();
        var finished = new AtomicBoolean(false);

        response.subscribe(
                chunk -> {
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var choice = chunk.getChoices().getFirst();
                        if (choice.getText() != null) {
                            content.append(choice.getText());
                        }
                    }
                },
                error -> {
                    throw new RuntimeException(error);
                },
                () -> {
                    finished.set(true);
                    System.out.println("Response: " + content);
                    assertNotNull(content.toString());
                    assertTrue(content.toString().toLowerCase().contains("paris"));
                }
        );

        while (!finished.get()) {
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void testStructuredOutput() throws InterruptedException {
        var messages = new ArrayList<AgentMessage>();
        messages.add(AgentMessage.builder()
                .role(AgentMessage.ROLE.SYSTEM)
                .type(AgentMessage.TYPE.TEXT)
                .text("You are a helpful assistant.")
                .build());
        messages.add(AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.TEXT)
                .text("What is the weather in Paris? Give me a random temperature.")
                .build());

        var response = llm.send(messages, WeatherResponse.class, null);

        var toolCalls = new ArrayList<Output.ToolCall>();
        var finished = new AtomicBoolean(false);

        response.subscribe(
                chunk -> {
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var choice = chunk.getChoices().getFirst();
                        if (choice.getToolCall() != null) {
                            toolCalls.addAll(choice.getToolCall());
                        }
                    }
                },
                error -> {
                    throw new RuntimeException(error);
                },
                () -> {
                    finished.set(true);
                    System.out.println("Tool calls: " + toolCalls);
                    assertFalse(toolCalls.isEmpty());
                    var toolCall = toolCalls.getFirst();
                    assertEquals("output_format", toolCall.getName());
                    assertNotNull(toolCall.getArguments());
                }
        );

        while (!finished.get()) {
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void testToolCall() throws InterruptedException {
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

        var response = llm.send(messages, null, List.of(tool1));

        var toolCalls = new ArrayList<Output.ToolCall>();
        var finished = new AtomicBoolean(false);

        response.subscribe(
                chunk -> {
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var choice = chunk.getChoices().getFirst();
                        if (choice.getToolCall() != null) {
                            toolCalls.addAll(choice.getToolCall());
                        }
                    }
                },
                error -> {
                    throw new RuntimeException(error);
                },
                () -> {
                    finished.set(true);
                    System.out.println("Tool calls: " + toolCalls);
                    assertFalse(toolCalls.isEmpty());
                }
        );

        while (!finished.get()) {
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void testThinking() throws InterruptedException {
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
                .text("Solve this math problem: If a train travels 120 km in 2 hours, what is its average speed? Explain your reasoning.")
                .build());

        var response = thinkingLlm.send(messages, null, null);

        var content = new StringBuilder();
        var thinking = new StringBuilder();
        var finished = new AtomicBoolean(false);

        response.subscribe(
                chunk -> {
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var choice = chunk.getChoices().getFirst();
                        if (choice.getText() != null) {
                            content.append(choice.getText());
                        }
                        if (choice.getThinking() != null) {
                            thinking.append(choice.getThinking());
                        }
                    }
                },
                error -> {
                    throw new RuntimeException(error);
                },
                () -> {
                    finished.set(true);
                    System.out.println("Thinking: " + thinking);
                    System.out.println("Content: " + content);
                    assertTrue(content.length() > 0);
                }
        );

        while (!finished.get()) {
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
    void testImageInput() throws InterruptedException {
        var messages = new ArrayList<AgentMessage>();
        messages.add(AgentMessage.builder()
                .role(AgentMessage.ROLE.USER)
                .type(AgentMessage.TYPE.IMAGE)
                .files(List.of(
                        "https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_PNG_500kB.png"
                ))
                .text("What color is this image?")
                .build());

        var response = llm.send(messages, null, null);

        var content = new StringBuilder();
        var finished = new AtomicBoolean(false);

        response.subscribe(
                chunk -> {
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var choice = chunk.getChoices().getFirst();
                        if (choice.getText() != null) {
                            content.append(choice.getText());
                        }
                    }
                },
                error -> {
                    throw new RuntimeException(error);
                },
                () -> {
                    finished.set(true);
                    System.out.println("Image response: " + content);
                    assertNotNull(content.toString());
                    assertTrue(content.length() > 0);
                }
        );

        while (!finished.get()) {
            Thread.sleep(100);
        }
    }

    @Test
    @Timeout(30)
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

        System.out.println("Query response: " + choice.getText());
    }

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

        assertThrows(RuntimeException.class, () -> {
            llm.batch(messageBatch, null);
        });
    }

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
        assertEquals("街道地址", addressProperties.path("street").path("description").asText());
        assertEquals("string", addressProperties.path("city").path("type").asText());
        assertEquals("城市名称", addressProperties.path("city").path("description").asText());
        assertEquals("string", addressProperties.path("zipcode").path("type").asText());
        assertEquals("邮政编码", addressProperties.path("zipcode").path("description").asText());
    }
}
