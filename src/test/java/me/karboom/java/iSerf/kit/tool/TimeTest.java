package me.karboom.java.iSerf.kit.tool;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.agent.tool.Loader;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.kit.tool.time.AddPeriodicPlan;
import me.karboom.java.iSerf.kit.tool.time.AddAbsolutePlan;
import me.karboom.java.iSerf.kit.tool.time.AddRelativePlan;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class TimeTest {

    private OpenAITest llmTest = new OpenAITest();

    @Test
    public void testAddPlan() throws InterruptedException, IOException {
        // 读取测试数据 JSON 文件
        var testResourcePath = "src/test/resources/kit/tool/time-addplan-testdata.json";
        var jsonContent = Files.readString(Path.of(testResourcePath));
        var testCases = JSONUtil.parseArray(jsonContent);

        var results = new CopyOnWriteArrayList<Map<String, Object>>();

        for (var i = 0; i < testCases.size(); i++) {
            var testCase = testCases.get(i);
            var description = testCase.path("description").asText("");
            var prompt = testCase.path("prompt").asText("");

            log.debug("testAddPlan: 执行测试用例 {} - {}", i + 1, description);

            var resultContainer = new StringBuilder();
            var successContainer = new AtomicBoolean(false);

            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                // 通过 Loader 从类名加载工具
                var loader = new Loader(30);
                var addPeriodicPlanTool = loader.fromFunction(AddPeriodicPlan.class);
                var addAbsolutePlanTool = loader.fromFunction(AddAbsolutePlan.class);
                var addRelativePlanTool = loader.fromFunction(AddRelativePlan.class);

                var tools = new ArrayList<Tool<?>>();
                tools.add(addPeriodicPlanTool);
                tools.add(addAbsolutePlanTool);
                tools.add(addRelativePlanTool);

                var system = """
                        你是一个私人助理
                        """;

                var llm = llmTest.getLlm();
                var llmProvider = new FixedLlmProvider(llm);
                var agent = new Agent("time-agent-" + System.currentTimeMillis(), "", llmProvider, tools) {}.run();

                var result = new StringBuilder();
                var finished = new AtomicBoolean(false);

                agent.subscribe(
                        item -> {
                            if (item.getIsSegment().equals(0)) {
                                result.append(item.getText());
                                System.out.println("addPlan 结果：" + item.getText());
                                finished.set(true);
                            }
                        }
                );

                agent.send(system + prompt);

                while (!finished.get()) {
                    Thread.sleep(100);
                }

                assertNotNull(result.toString(), "addPlan 结果不应为空");
                assertTrue(
                        result.toString().contains("计划添加成功") || result.toString().contains("ID"),
                        "应该返回计划添加成功的消息"
                );

                resultContainer.append(result);
                successContainer.set(true);
            });

            // 记录测试结果
            results.add(Map.of(
                    "index", i,
                    "description", description,
                    "prompt", prompt,
                    "success", successContainer.get(),
                    "response", resultContainer.toString()
            ));
        }

        // 输出测试结果到 JSON 文件
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        var outputPath = "src/test/resources/kit/tool/time-addplan-results-%s.json".formatted(timestamp);
        var resultsJson = JSONUtil.stringify(results);
        Files.writeString(Path.of(outputPath), resultsJson);
        System.out.println("测试结果已保存到：" + outputPath);
    }
}
