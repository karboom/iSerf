package me.karboom.java.iSlogger.llm.text;

import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.memory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 测试类
 * 注意：这些测试需要真实的 API Key 才能运行
 */
class OpenAITest {
    private String apiKey;
    private String url;
    private Map<String, Object> llmConfig;

    private OpenAI llm;

    @BeforeEach
    void setUp() {
        // 从环境变量获取 API Key（测试时需要设置）
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
        llmConfig.put("model", "gpt-4");
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);
        llmConfig.put("top_p", 0.9);

        llm = new OpenAI("qwen-plus", llmConfig, apiKey, url, 1);
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
                .role("system")
                .text("You are a helpful assistant.")
                .build());
        messages.add(Item.builder()
                .role("user")
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
                .role("system")
                .text("You are a helpful assistant.")
                .build());
        messages.add(Item.builder()
                .role("user")
                .text("What is the capital of France?")
                .build());
        messages.add(Item.builder()
                .role("assistant")
                .text("The capital of France is Paris.")
                .build());
        messages.add(Item.builder()
                .role("user")
                .text("柏林天气如何")
                .build());

        var p1 = new Tool.Parameter("location", "string", "地点", true);
        var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true);
        var tool1 = Tool.builder().name("query").description("当你需要查询天气，使用这个工具").parameters(List.of(p1, p2)).build();


        var response = llm.send(messages, null, List.of(tool1));

//        response.subscribe(chunk -> {
//
//            System.out.println(chunk);
//            System.out.println(chunk.choices().getFirst().delta().content());
//        }).onCompleteFuture().whenComplete((result, error) -> {
//            if (error == null) {
//                System.out.println("done");
//            } else {
//                System.out.println(error);
//            }
//        });

        Thread.sleep(10000);

        assertNotNull(response);
    }

}
