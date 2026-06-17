package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.agent.tool.CallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 性能测试类
 */
class AgentPerfTest {

    private Map<String, Object> llmConfig;
    private List<Tool<?>> tools;
    private OpenAITest llmTest;

    @BeforeEach
    void setUp() {
        // 初始化 llmTest
        llmTest = new OpenAITest();

        // 创建测试工具
        tools = new ArrayList<>();
        var weatherTool = Tool.<Map>builder()
                .name("getWeather")
                .description("Get the current weather for a location")
                .parameters(List.of(
                        new Tool.Parameter("location", "string", "The city name", true, null),
                        new Tool.Parameter("unit", "string", "Temperature unit (celsius or fahrenheit)", false, null)
                ))
                .type(Tool.TYPE.FUNCTION)
                .function((ctx, params) -> CallResult.builder()
                        .llm((Math.random() * 15 + 15) + "摄氏度")
                        .error(null)
                        .direct(null)
                        .build())
                .build();
        tools.add(weatherTool);

        var timeTool = Tool.<Map>builder()
                .name("GetTime")
                .description("Get the current time")
                .parameters(List.of())
                .type(Tool.TYPE.IFUNCTION)
                .iDirectory("/home/karboom/projects/karboom/java/iSlogger/class")
                .build();
        tools.add(timeTool);
    }

    @Test
    void largeCountAgent() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            // 创建 Agent
            var llm = llmTest.getLlm();
            var llmProvider = new FixedLlmProvider(llm);

            // 统一的提示词
            var prompt = "你是一个有用的助手";

            // 创建 1 万个 agent
            var agentCount = 10000;
            var agents = new ArrayList<Agent>();
            var completedCount = new AtomicInteger(0);
            var latch = new CountDownLatch(agentCount);

            System.out.println("开始创建 " + agentCount + " 个 agent...");
            var startTime = System.currentTimeMillis();

            // 创建所有 agent
            for (var i = 0; i < agentCount; i++) {
                var agentId = "perf-agent-" + i;
                var config = new AgentConfig();
                var metadata = new AgentMetadata();
                metadata.setId(agentId);
                config.setMetadata(metadata);
                config.setPrompt(prompt);
                config.setLlm(llmProvider);
                config.setTools(tools);
                var agent = new Agent(config) {}.run();

                // 订阅 broadcast
                agent.subscribe(item -> {
                    if (item.getIsSegment() == 0) {
                        completedCount.incrementAndGet();
//                        latch.countDown();
                    }
                });

                agents.add(agent);
            }

            var creationTime = System.currentTimeMillis() - startTime;
            System.out.println("创建 " + agentCount + " 个 agent 耗时：" + creationTime + "ms");

            // 发送消息
            System.out.println("开始发送消息...");
            var sendStartTime = System.currentTimeMillis();

            for (var i = 0; i < agentCount; i++) {
//                agents.get(i).send("你好");
            }

            var sendTime = System.currentTimeMillis() - sendStartTime;
            System.out.println("发送 " + agentCount + " 条消息耗时：" + sendTime + "ms");

            // 等待所有响应完成
            System.out.println("等待所有 agent 响应完成...");
            var waitStartTime = System.currentTimeMillis();

//            latch.await();

            var waitTime = System.currentTimeMillis() - waitStartTime;
            var totalTime = System.currentTimeMillis() - startTime;

            System.out.println("等待完成耗时：" + waitTime + "ms");
            System.out.println("总耗时：" + totalTime + "ms");
            System.out.println("完成的 agent 数量：" + completedCount.get());

            // 验证所有 agent 都完成了
            assertEquals(agentCount, completedCount.get(), "所有 agent 都应完成响应");
        });
    }


}