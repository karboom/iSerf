package agent;

import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 测试类
 */
class AgentTest {
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
        var weatherTool = new Tool(
                "get-weather",
                "Get the current weather for a location",
                List.of(
                        new Tool.Parameter("location", "string", "The city name", true),
                        new Tool.Parameter("unit", "string", "Temperature unit (celsius or fahrenheit)", false)
                )
        );
        tools.add(weatherTool);
    }

    @Test
    void testAgentTalk() {
        // 创建 Agent
        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 3);
        var agent = new Agent("test-agent", llm, tools) {};

//        // 测试1: 基本 talk 方法，验证返回的是 Flux
//        var message1 = "Hello, how are you?";
//        var response1 = agent.talk(message1);
//        assertNotNull(response1, "响应不应为空");
//        assertTrue(response1 instanceof Flux, "响应应该是 Flux 类型");
//
//        // 测试2: 验证 Flux 可以正常消费（使用 StepVerifier）
//        StepVerifier.create(response1)
//                .expectNextMatches(chunk -> chunk != null)
//                .thenCancel()
//                .verify();
//
//        // 测试3: 多条消息
//        var response2 = agent.talk("How are you?");
//        assertNotNull(response2, "第二条消息响应不应为空");
//        var response3 = agent.talk("What's your name?");
//        assertNotNull(response3, "第三条消息响应不应为空");
//
//        // 测试4: 空消息
//        var responseEmpty = agent.talk("");
//        assertNotNull(responseEmpty, "空消息响应不应为空");

        // 测试5: 带工具的 Agent，验证工具调用场景
        var agentWithTools = new Agent("test-agent-tools", llm, tools) {};
        var responseWithTools = agentWithTools.talk("What's the weather in Beijing?").doOnNext(System.out::println);
        assertNotNull(responseWithTools, "带工具的响应不应为空");
        assertTrue(responseWithTools instanceof Flux, "带工具的响应应该是 Flux 类型");
        StepVerifier.create(responseWithTools).expectNextCount(3).verifyComplete();
    }
}
