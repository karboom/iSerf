package me.karboom.java.iSerf.llm.text;

import me.karboom.java.iSerf.memory.Item;
import me.karboom.java.iSerf.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 测试类
 * 注意：这些测试需要本地运行的 Ollama 服务
 * 启动 Ollama: ollama serve
 */
public class OllamaTest {

    private Ollama llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    public Ollama getLlm() {
        return this.getLlm("qwen2.5:7b");
    }

    public Ollama getLlm(String model) {
        // Ollama 通常不需要 API Key
        var apiKey = System.getenv("OLLAMA_API_KEY");
        var url = System.getenv("OLLAMA_URL");

        if (url == null || url.isEmpty()) {
            url = "http://localhost:11434";
        }

        // 配置 LLM 参数
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);
        llmConfig.put("top_p", 0.9);

        var llm = new Ollama(model, llmConfig, apiKey, url, 1);

        return llm;
    }

    static class WeatherResponse {
        public String location;
        public String weather;
        public Integer temperature;
    }

    @Test
    void testSend() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.SYSTEM)
                    .type(Item.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("What is the capital of France?")
                    .build());

            var response = llm.send(messages, null, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        System.out.println("Chunk: " + chunk);
                        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                            var text = chunk.getChoices().getFirst().getText();
                            if (text != null) {
                                content.append(text);
                            }
                        }
                    },
                    error -> {
                        error.printStackTrace();
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Response content: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().toLowerCase().contains("paris"));
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @Test
    void testOutputFormat() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.SYSTEM)
                    .type(Item.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("北京的气温是多少度？用 JSON 格式回答。")
                    .build());

            var response = llm.send(messages, WeatherResponse.class, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                            var text = chunk.getChoices().getFirst().getText();
                            if (text != null) {
                                content.append(text);
                            }
                        }
                    },
                    error -> {
                        error.printStackTrace();
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Response content: " + content);
                        assertNotNull(content.toString());
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @Test
    void testQuery() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.SYSTEM)
                    .type(Item.TYPE.TEXT)
                    .text("你是一个乐于助人的助手。")
                    .build());
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("1+1 等于几？")
                    .build());

            var response = llm.query(messages, null);

            assertNotNull(response);
            assertNotNull(response.getChoices());
            assertFalse(response.getChoices().isEmpty());

            var choice = response.getChoices().getFirst();
            assertNotNull(choice.getText());
            assertTrue(choice.getText().length() > 0);

            System.out.println("Query response: " + choice.getText());
        });
    }

    @Test
    void testBatchNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> {
            var messages = new ArrayList<List<Item>>();
            var msg = new ArrayList<Item>();
            msg.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("Hello")
                    .build());
            messages.add(msg);

            llm.batch(messages, null);
        });
    }

    @Test
    void testTaskStatusNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> {
            llm.taskStatus("test-id");
        });
    }

    @Test
    void testTaskResultNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> {
            var task = BatchTaskInfo.builder().id("test-id").build();
            llm.taskResult(task);
        });
    }

    @BeforeEach
    void setupExceptionHandler() {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            System.err.println("🔥 线程 [" + t.getName() + "] 未捕获异常:");
            e.printStackTrace();
        });
    }
}