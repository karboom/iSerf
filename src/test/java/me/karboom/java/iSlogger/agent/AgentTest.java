package me.karboom.java.iSlogger.agent;

import me.karboom.java.iSlogger.llm.text.OpenAITest;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.tool.FunctionWrapper;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    private Map<String, Object> llmConfig;
    private List<Tool> tools;
    private OpenAITest llmTest;


    @BeforeEach
    void setUp() {
        // 初始化 llmTest
        llmTest = new OpenAITest();

        // 创建测试工具
        tools = new ArrayList<>();
        var weatherTool = Tool.builder()
                .name("getWeather")
                .description("Get the current weather for a location")
                .parameters(List.of(
                        new Tool.Parameter("location", "string", "The city name", true),
                        new Tool.Parameter("unit", "string", "Temperature unit (celsius or fahrenheit)", false)
                ))
                .type(Tool.TYPE.FUNCTION)
                .function(params -> (Math.random() * 15 + 15) + "摄氏度")

                .build();
        tools.add(weatherTool);

        var timeTool = Tool.builder()
                .name("GetTime")
                .description("Get the current time")
                .parameters(List.of())
                .type(Tool.TYPE.IFUNCTION)
                .iFunction("/home/karboom/projects/karboom/java/iSlogger/class/time")
                .build();
        tools.add(timeTool);
    }

    @Test
    public void testAgentBroadcast() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
            var llm = llmTest.getLlm();
//            llm.llmConfig.put("thinking", true);

            // 定义输入列表
            var inputs = List.of(
                    "杭州的天气如何，上海的天气如何"
//                    "写首五言律诗，中文的",
//                    "你好"
            );

            // 创建表格
            var inputColumn = StringColumn.create("input", inputs);
            var outputColumn = StringColumn.create("output", new String[inputs.size()]);
            var table = Table.create("agent_broadcast_test", inputColumn, outputColumn);

            // 完成计数器
            var completedCount = new AtomicInteger(0);

            // 对于每个输入，发送消息并订阅响应
            inputs.forEach(input -> {
                var i = inputs.indexOf(input);

                var agent = new Agent("test-agent-" + i, "", llm, tools) {};

                // 订阅 broadcast
                agent.subscribe(item -> {
                    System.out.println("Index " + i + " received: " + item);
                    if (item.getIsSegment() == 0) {
                        outputColumn.set(i, item.getText());
                        completedCount.incrementAndGet();
                    }
                });

                // 发送消息
                agent.send(input);
            });

            // 等待所有输出完成
            while (completedCount.get() < inputs.size()) {
                Thread.sleep(100);
            }

            // 输出表格
            System.out.println(table.print());
        });
    }

    @Test
    void testUpdateTool() {
        // 创建 Agent
        var llm = llmTest.getLlm();
        var agent = new Agent("test-agent", "", llm, tools) {};

        // 测试用例1: 正常情况 - 工具存在且更新成功
        var toolCall = me.karboom.java.iSlogger.memory.Item.ToolCall.builder()
                .name("getWeather")
                .arguments(new HashMap<>(){{
                    put("location", "伦敦");
                }})
                .result(me.karboom.java.iSlogger.memory.Item.ToolCall.Result.builder()
                        .error("这不是一个中国的地点")
                        .build())
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
                .result(me.karboom.java.iSlogger.memory.Item.ToolCall.Result.builder()
                        .error("这不是一个中国的地点")
                        .build())
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
        var llm = llmTest.getLlm();
        var agent = new Agent("test-agent", "", llm, tools) {};

        var result = Item.ToolCall.Result.builder()
                .error("时间格式不正确")
                .build();

        // 测试用例1: 正常情况 - 本地工具存在且更新成功
        var toolCall = Item.ToolCall.builder()
                .name("GetTime")
                .arguments(new HashMap<>())
                .result(result)
                .build();

        var updateMono = agent.updateToolLocal(toolCall);
        assertNotNull(updateMono, "updateToolLocal should return a Mono<Void>");
        assertDoesNotThrow(() -> updateMono.block(), "updateToolLocal should succeed for existing local tool");

    }

    @Test
    void testEvolution() throws InterruptedException {
        // 创建 Agent
        var llm = llmTest.getLlm();
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
}
