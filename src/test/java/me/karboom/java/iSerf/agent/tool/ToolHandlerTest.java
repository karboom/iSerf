package me.karboom.java.iSerf.agent.tool;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.llm.text.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolHandlerTest {

    private List<Tool<?>> tools;
    private ToolHandler toolHandler;
    private Agent testAgent;

    static class ToolParam {
        public String location;
        public String unit;
    }

    @BeforeEach
    void setUp() {
        var llmTest = new OpenAITest();
        var llmProvider = new FixedLlmProvider(llmTest.getLlm());

        tools = new ArrayList<>();

        var weatherT = new Tool<ToolParam>(){};
        weatherT.setType(Tool.TYPE.FUNCTION);
        weatherT.setDescription("Get the current weather for a location");
        weatherT.setName("getWeather");
        weatherT.setFunction((ctx, params) -> {
            return CallResult.builder().llm((Math.random() * 15 + 15) + "摄氏度").build();
        });
        tools.add(weatherT);

        toolHandler = new ToolHandler(tools, 5);
        var config = new AgentConfig();
        var metadata = new AgentMetadata();
        metadata.setId("test-agent");
        config.setMetadata(metadata);
        config.setPrompt("test prompt");
        config.setLlm(llmProvider);
        config.setTools(tools);
        testAgent = new Agent(config);
    }

    @Test
    void testUpdate() {
        var toolCall = AgentMessage.ToolCall.builder()
                .name("getWeather")
                .arguments(new HashMap<>(){{
                    put("location", "伦敦");
                }})
                .result(CallResult.builder()
                        .error(new RuntimeException("这不是一个中国的地点"))
                        .build())
                .build();

        var updateMono = toolHandler.update(testAgent, toolCall);
        assertNotNull(updateMono, "update should return a Mono<Void>");
    }

    @Test
    void testUpdateWithNonExistentTool() {
        var nonExistentToolCall = AgentMessage.ToolCall.builder()
                .name("non-existent-tool")
                .arguments(new HashMap<>())
                .result(CallResult.builder()
                        .error(new RuntimeException("错误"))
                        .build())
                .build();

        var errorMono = toolHandler.update(testAgent, nonExistentToolCall);
        assertNotNull(errorMono, "update should return a Mono<Void> even for errors");
        assertThrows(RuntimeException.class, () -> errorMono.block(),
                "update should throw RuntimeException for non-existent tool");
    }

    // endregion

    // region ========== 构造函数 ==========

    @Test
    void testConstructor() {
        assertNotNull(toolHandler.getTools());
        assertEquals(1, toolHandler.getTools().size());
        assertNotNull(toolHandler.getCaches());
        assertTrue(toolHandler.getCaches().isEmpty());
    }

    // endregion

    // region ========== addCache ==========

    @Test
    void testAddCache() {
        var cache = CallCache.builder()
                .callId("call-001")
                .toolName("getWeather")
                .params(new HashMap<>())
                .build();

        toolHandler.addCache(cache);

        assertEquals(1, toolHandler.getCaches().size());
        assertEquals("call-001", toolHandler.getCaches().get(0).getCallId());
    }

    // endregion

    // region ========== merge ==========

    @Test
    void testMergeEmptyChunks() {
        var result = toolHandler.merge(new ArrayList<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testMergeWithToolCalls() {
        var chunk1 = Output.builder()
                .choices(List.of(Output.Choice.builder()
                        .toolCall(List.of(Output.ToolCall.builder()
                                .index(0)
                                .id("call-001")
                                .name("getWeather")
                                .arguments("{\"location\":\"")
                                .build()))
                        .build()))
                .build();

        var chunk2 = Output.builder()
                .choices(List.of(Output.Choice.builder()
                        .toolCall(List.of(Output.ToolCall.builder()
                                .index(0)
                                .arguments("Beijing\"}")
                                .build()))
                        .build()))
                .build();

        var result = toolHandler.merge(List.of(chunk1, chunk2));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());

        var mergedCall = result.get(0).get(0);
        assertEquals("call-001", mergedCall.getId());
        assertEquals("getWeather", mergedCall.getName());
        assertEquals("Beijing", mergedCall.getArguments().get("location"));
    }

    @Test
    void testMergeWithMultipleChoices() {
        var chunk1 = Output.builder()
                .choices(List.of(Output.Choice.builder()
                        .toolCall(List.of(
                                Output.ToolCall.builder()
                                        .index(0)
                                        .id("call-001")
                                        .name("getWeather")
                                        .arguments("{\"location\":\"London\"}")
                                        .build(),
                                Output.ToolCall.builder()
                                        .index(1)
                                        .id("call-002")
                                        .name("getWeather")
                                        .arguments("{\"location\":\"Tokyo\"}")
                                        .build()
                        ))
                        .build()))
                .build();

        var result = toolHandler.merge(List.of(chunk1));

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("London", result.get(0).get(0).getArguments().get("location"));
        assertEquals("Tokyo", result.get(1).get(0).getArguments().get("location"));
    }

    // endregion

}