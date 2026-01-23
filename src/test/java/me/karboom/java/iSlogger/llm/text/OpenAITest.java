package me.karboom.java.iSlogger.llm.text;

import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.memory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 测试类
 * 注意：这些测试需要真实的 API Key 才能运行
 */
public class OpenAITest {

    private OpenAI llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    public OpenAI getLlm() {

        // 从环境变量获取 API Key（测试时需要设置）
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        // 如果环境变量未设置，使用测试默认值
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "sk-1d926b2b2c614ca09e3a6d89a9851ea4";
        }
        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        // 配置 LLM 参数
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);
        llmConfig.put("top_p", 0.9);

        var llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 1);

        return llm;
    }


    static class WeatherResponse {
        public String location;
        public String weather;
        public Integer temperature;
    }

    @Test
    void testOutputFormat() throws InterruptedException {
        var messages = new ArrayList<Item>();
        messages.add(Item.builder()
                .role(Item.ROLE.SYSTEM)
                .text("You are a helpful assistant.")
                .build());
        messages.add(Item.builder()
                .role(Item.ROLE.USER)
                .text("What is the weather in Paris? Give me a random temperature.")
                .build());

        var response = llm.send(messages, WeatherResponse.class, null);

        var content = new StringBuilder();
        response.doOnNext(chunk -> {
            var delta = chunk.choices().getFirst().delta().content();
            if (delta.isPresent()) {
                content.append(delta.get());
            }
        }).blockLast();

        System.out.println("Response content: " + content);
        assertNotNull(content.toString());
        assertTrue(content.toString().contains("Paris"));
    }

    @Test
    void testSendWithMultipleMessages() throws InterruptedException {

        // 创建多条测试消息
        var messages = new ArrayList<Item>();
        messages.add(Item.builder()
                .role(Item.ROLE.SYSTEM)
                .text("You are a helpful assistant.")
                .build());
        messages.add(Item.builder()
                .role(Item.ROLE.USER)
                .text("What is the capital of France?")
                .build());
        messages.add(Item.builder()
                .role(Item.ROLE.ASSISTANT)
                .text("The capital of France is Paris.")
                .build());
        messages.add(Item.builder()
                .role(Item.ROLE.USER)
                .text("柏林天气如何")
                .build());

        var p1 = new Tool.Parameter("location", "string", "地点", true);
        var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true);
        var tool1 = Tool.builder().name("query").description("当你需要查询天气，使用这个工具").parameters(List.of(p1, p2)).build();


        var response = llm.send(messages, null, List.of(tool1));

        Thread.sleep(10000);

        assertNotNull(response);
    }

    @Test
    void testThinking() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .text("弄一幅对联")
                    .build());

            var response = llm.send(messages, null, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        var delta = chunk.choices().getFirst().delta();
                        var x = delta._additionalProperties();
                        var y = x.get("reasoning_content");

                    }
            );

            // 等待完成或超时
            while (!finished.get()) {
                Thread.sleep(100);
            }

            System.out.println("Thinking response: " + content);
            assertNotNull(content.toString());
            assertTrue(content.toString().length() > 0);
        });
    }
}
