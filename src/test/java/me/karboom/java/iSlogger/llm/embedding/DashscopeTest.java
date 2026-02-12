package me.karboom.java.iSlogger.llm.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dashscope 向量模型测试类
 */
public class DashscopeTest {


    /**
     * 创建Dashscope实例的辅助方法
     */
    private Dashscope createDashscope(String model) {
        var apiKey = System.getenv("OPENAI_API_KEY");

        
        var config = new HashMap<String, Object>();

        
        return new Dashscope(model, config, apiKey);
    }

    @Test
    void testTextEmbeddingV1() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dashscope = createDashscope("text-embedding-v1");
            var inputs = List.of(Input.builder().text("Hello, world!").build());
            
            var output = dashscope.convert(inputs);
            assertNotNull(output);
            assertNotNull(output.getResult());
            assertFalse(output.getResult().isEmpty());
            var firstResult = output.getResult().get(0);
            assertNotNull(firstResult.getEmbedding());
            assertTrue(firstResult.getEmbedding().size() > 0);
            assertEquals("text", firstResult.getType());
            assertNotNull(output.getUsage());
        });
    }

    @Test
    void testMultimodalEmbedding() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dashscope = createDashscope("qwen3-vl-embedding");
            var inputs = new ArrayList<Input>();
            
            // 纯文本输入
            inputs.add(Input.builder().text("Hello, world!").build());
            
            // 文本+图片输入
            inputs.add(Input.builder()
                .text("A beautiful sunset")
                .image("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_PNG_500kB.png")
                .build());
            
            var output = dashscope.convert(inputs);
            assertNotNull(output);
            assertNotNull(output.getResult());
            assertFalse(output.getResult().isEmpty());
            var firstResult = output.getResult().get(0);
            assertNotNull(firstResult.getEmbedding());
            assertTrue(firstResult.getEmbedding().size() > 0);
            // 多模态的type可能是"text"或其他，具体取决于API响应
            assertNotNull(firstResult.getType());
            assertNotNull(output.getUsage());
        });
    }


    @Test
    void testMultipleTextInputs() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var dashscope = createDashscope("text-embedding-v2");
            var inputs = List.of(
                Input.builder().text("First sentence").build(),
                Input.builder().text("Second sentence").build(),
                Input.builder().text("Third sentence").build()
            );
            
            var output = dashscope.convert(inputs);
            assertNotNull(output);
            assertNotNull(output.getResult());
            assertFalse(output.getResult().isEmpty());
            var firstResult = output.getResult().get(0);
            assertNotNull(firstResult.getEmbedding());
            assertTrue(firstResult.getEmbedding().size() > 0);
            assertEquals("text", firstResult.getType());
            assertNotNull(output.getUsage());
        });
    }


}