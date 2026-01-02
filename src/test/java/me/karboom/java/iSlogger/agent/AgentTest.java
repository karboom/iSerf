package me.karboom.java.iSlogger.agent;

import me.karboom.java.iSlogger.tool.FunctionWrapper;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 测试类
 */
class AgentTest {


    static class Result {
        public List<String> items;

        public Result() {}
    }

    private String apiKey;
    private String url;
    private Map<String, Object> llmConfig;
    private List<Tool> tools;


    @BeforeEach
    void setUp() {
        // 从环境变量获取 API Key
        apiKey = System.getenv("OPENAI_API_KEY");
        url = System.getenv("OPENAI_API_URL");

        // 如果环境变量未设置，使用测试默认值
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "sk-1d926b2b2c614ca09e3a6d89a9851ea4";
        }
        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }


        // 配置 LLM 参数
        llmConfig = new HashMap<>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);

        // 创建测试工具
        tools = new ArrayList<>();
        var weatherTool = Tool.builder()
                .name("getWeather")
                .description("Get the current weather for a location")
                .parameters(List.of(
                        new Tool.Parameter("location", "string", "The city name", true),
                        new Tool.Parameter("unit", "string", "Temperature unit (celsius or fahrenheit)", false)
                ))
                .type("function")
                .function(params -> "28摄氏度")

                .build();
        tools.add(weatherTool);

        var timeTool = Tool.builder()
                .name("GetTime")
                .description("Get the current time")
                .parameters(List.of())
                .type("iClass")
                .iFunction("/home/karboom/projects/karboom/java/iSlogger/class/time")
                .build();
        tools.add(timeTool);
    }

    @Test

    void testAgentBroadcast() throws InterruptedException {
        // 创建 Agent
        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 3);
        var agent = new Agent("test-agent", "", llm, tools) {};

        // 创建一个列表来收集广播的消息
        var receivedMessages = new ArrayList<String>();
        
        // 订阅 broadcast 流
        var subscription = agent.subscribe(
            item -> {
                System.out.println(JSONUtil.stringify(item));
                receivedMessages.add(JSONUtil.stringify(item));
            }
//            error -> fail("Broadcast stream should not emit errors: " + error.getMessage()),
//            () -> receivedMessages.add("COMPLETED")
        );

        // 使用 send 方法发送消息
//        agent.send("写一个100字散文，关于宇宙");
        agent.send("杭州的天气如何，上海的天气如何");
//        agent.send("现在是什么时间");
//        agent.send("列举三个哺乳动物", Result.class);
//        agent.send("写首五言律诗");

        // 等待一段时间让消息被处理
        Thread.sleep(10000);

        System.out.println(receivedMessages);
        // 验证是否收到了消息
        assertEquals(2, receivedMessages.size(), "Should have received 2 messages");
        assertEquals("Hello, broadcast!", receivedMessages.get(0), "First message should match");
        assertEquals("Hello, subscriber!", receivedMessages.get(1), "Second message should match");
        
        // 清理订阅
        subscription.dispose();
    }

    @Test
    void testUpdateTool() {
        // 创建 Agent
        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 3);
        var agent = new Agent("test-agent", "", llm, tools) {};

        // 测试用例1: 正常情况 - 工具存在且更新成功
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var resultNode = objectMapper.createObjectNode();
        resultNode.put("type", "error");
        resultNode.put("content", "这不是一个中国的地点");

        var toolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("getWeather")
                .arguments(new HashMap<>(){{
                    put("location", "伦敦");
                }})
                .result(resultNode)
                .build();

        // 验证 Mono<Void> 返回值可以正确处理
        var updateToolMono = agent.updateTool(toolCall);
        assertNotNull(updateToolMono, "updateTool should return a Mono<Void>");
        // 订阅 Mono 以触发执行，并验证不会抛出异常
        assertDoesNotThrow(() -> updateToolMono.block(), "updateTool should not throw exception for valid tool");

        // 测试用例2: 工具不存在的情况
        var nonExistentToolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("non-existent-tool")
                .arguments(new HashMap<>())
                .result(resultNode)
                .build();

        // 验证 Mono<Void> 返回值可以正确处理
        var errorMono = agent.updateTool(nonExistentToolCall);
        assertNotNull(errorMono, "updateTool should return a Mono<Void> even for errors");
        // 验证会抛出预期的异常
        assertThrows(RuntimeException.class, () -> errorMono.block(), 
                   "updateTool should throw RuntimeException for non-existent tool");
    }

    @Test
    void testUpdateToolLocal() {
        // 创建 Agent
        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 3);
        var agent = new Agent("test-agent", "", llm, tools) {};

        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var resultNode = objectMapper.createObjectNode();
        resultNode.put("type", "error");
        resultNode.put("content", "时间格式不正确");

        // 测试用例1: 正常情况 - 本地工具存在且更新成功
        var toolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("GetTime")
                .arguments(new HashMap<>())
                .result(resultNode)
                .build();

        var updateMono = agent.updateToolLocal(toolCall);
        assertNotNull(updateMono, "updateToolLocal should return a Mono<Void>");
        assertDoesNotThrow(() -> updateMono.block(), "updateToolLocal should succeed for existing local tool");

        // 测试用例2: 工具不存在的情况
        var nonExistentToolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("non-existent-tool")
                .arguments(new HashMap<>())
                .result(resultNode)
                .build();

        var errorMono = agent.updateToolLocal(nonExistentToolCall);
        assertThrows(RuntimeException.class, () -> errorMono.block(),
                "updateToolLocal should throw RuntimeException for non-existent tool");

        // 测试用例3: Java文件不存在的情况
        var noFileTool = Tool.builder()
                .name("NoFileTool")
                .type("iClass")
                .iFunction("/non/existent/path")
                .build();
        var toolsWithNoFile = new ArrayList<>(tools);
        toolsWithNoFile.add(noFileTool);
        var agentWithNoFile = new Agent("test-agent-no-file", "", llm, toolsWithNoFile) {};

        var noFileToolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("NoFileTool")
                .arguments(new HashMap<>())
                .result(resultNode)
                .build();

        var noFileMono = agentWithNoFile.updateToolLocal(noFileToolCall);
        assertThrows(RuntimeException.class, () -> noFileMono.block(),
                "updateToolLocal should throw RuntimeException when java file is missing");
    }
}
