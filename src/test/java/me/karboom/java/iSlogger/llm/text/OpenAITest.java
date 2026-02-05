package me.karboom.java.iSlogger.llm.text;

import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.tool.Tool;
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
        return this.getLlm("qwen-plus");
    }

    public OpenAI getLlm(String model) {

        // 从环境变量获取 API Key（测试时需要设置）
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        // 配置 LLM 参数
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 1000);
        llmConfig.put("top_p", 0.9);

        var llm = new OpenAI(model, llmConfig, apiKey, url, 1);

        return llm;
    }


    static class WeatherResponse {
        public String location;
        public String weather;
        public Integer temperature;
    }


    @Test
    void testOutputFormat() throws InterruptedException {
        assertTimeout(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.SYSTEM)
                    .type(Item.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("What is the weather in Paris? Give me a random temperature.")
                    .build());

            var response = llm.send(messages, WeatherResponse.class, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        System.out.println(chunk);
                        if (chunk.getChoices() != null) {
                            content.append(chunk.getChoices().getFirst().getText());
                        }
                    },
                    error -> {
                        error.printStackTrace();
                        System.out.println();
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Response content: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().contains("巴黎") || content.toString().contains("Paris"));
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @Test
    void testToolCall() throws InterruptedException {
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
            messages.add(Item.builder()
                    .role(Item.ROLE.ASSISTANT)
                    .type(Item.TYPE.TEXT)
                    .text("The capital of France is Paris.")
                    .build());
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.TEXT)
                    .text("柏林天气如何，北京天气如何")
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true);
            var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true);
            var tool1 = Tool.builder().name("query").description("当你需要查询天气，使用这个工具").parameters(List.of(p1, p2)).build();

            var response = llm.send(messages, null, List.of(tool1));

            var content = new StringBuilder();
            var toolCalls = new ArrayList<String>();
            var finished = new AtomicBoolean(false);

            var holder = new ArrayList<Output>();
            response.subscribe(
                    chunk -> {
                        holder.add(chunk);
                    },
                    error -> {
                        error.printStackTrace();
                    },
                    () -> {
                        System.out.println("Response content: " + holder);
                        if (!toolCalls.isEmpty()) {
                            System.out.println("Tool calls: " + toolCalls);
                        }
                        assertNotNull(content);
                        finished.set(true);
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
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
            var thinking = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {

                    },
                    error -> {
                        throw new RuntimeException(error);
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Thinking response: " + thinking);
                        System.out.println("Content: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().length() > 0);
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @BeforeEach
    void setupExceptionHandler() {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            System.err.println("🔥 线程 [" + t.getName() + "] 未捕获异常:");
            e.printStackTrace();
        });
    }

    @Test
    void testImageInput() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();

            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.IMAGE)
                    .images(List.of(
                            "https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_PNG_500kB.png"
                    ))
                    .text("这张图片是什么颜色的？")
                    .build());

            var response = getLlm("qwen3-vl-plus").send(messages, null, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                            content.append(chunk.getChoices().getFirst().getText());
                        }
                    },
                    error -> {
                        throw new RuntimeException(error);
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Image response: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().length() > 0);
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @Test
    void testVideoInput() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.VIDEO)
                    .video("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_MP4_480_1_5MG.mp4")
                    .fps("5")
                    .text("描述这个视频的内容")
                    .build());

            var response = getLlm("qwen3-omni-flash").send(messages, null, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                            content.append(chunk.getChoices().getFirst().getText());
                        }
                    },
                    error -> {
                        throw new RuntimeException(error);
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Video response: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().length() > 0);
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }

    @Test
    void testAudioInput() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Item>();
            messages.add(Item.builder()
                    .role(Item.ROLE.USER)
                    .type(Item.TYPE.AUDIO)
                    .audio("https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSlogger/file_example_MP3_700KB.mp3")
                    .text("描述音频的内容")
                    .build());

            var response = getLlm("qwen3-omni-flash").send(messages, null, null);

            var content = new StringBuilder();
            var finished = new AtomicBoolean(false);

            response.subscribe(
                    chunk -> {
                        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                            content.append(chunk.getChoices().getFirst().getText());
                        }
                    },
                    error -> {
                        throw new RuntimeException(error);
                    },
                    () -> {
                        finished.set(true);
                        System.out.println("Audio response: " + content);
                        assertNotNull(content.toString());
                        assertTrue(content.toString().length() > 0);
                    }
            );

            while (!finished.get()) {
                Thread.sleep(100);
            }
        });
    }
}
