package me.karboom.java.iSerf.llm.text;

import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ArrayNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAIResponse Responses API 测试类
 * 注意：这些测试需要真实的 API Key 才能运行
 */
public class OpenAIResponseTest {

    private OpenAIResponse llm;

    @BeforeEach
    void setUp() {
        llm = getLlm();
    }

    public OpenAIResponse getLlm() {
        return this.getLlm("qwen-plus");
    }

    public OpenAIResponse getLlm(String model) {

        // 从环境变量获取 API Key（测试时需要设置）
        var apiKey = System.getenv("OPENAI_API_KEY");
        var url = System.getenv("OPENAI_API_URL");

        if (url == null || url.isEmpty()) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        // 配置 LLM 参数
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("top_p", 0.9);

        var llm = new OpenAIResponse(model, llmConfig, apiKey, url, 1);

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
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.SYSTEM)
                    .type(Message.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
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
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.SYSTEM)
                    .type(Message.TYPE.TEXT)
                    .text("You are a helpful assistant.")
                    .build());
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("What is the capital of France?")
                    .build());
            messages.add(Message.builder()
                    .role(Message.ROLE.ASSISTANT)
                    .type(Message.TYPE.TEXT)
                    .text("The capital of France is Paris.")
                    .build());
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("柏林天气如何，北京天气如何")
                    .build());

            var p1 = new Tool.Parameter("location", "string", "地点", true, null);
            var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true, null);
            var tool1 = Tool.<Map>builder().name("query").description("当你需要查询天气，使用这个工具").parameters(List.of(p1, p2)).build();

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
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
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
            var messages = new ArrayList<Message>();

            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.IMAGE)
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
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.VIDEO)
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
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.AUDIO)
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

    private List<List<Message>> createWeatherMessageBatch() {
        var messageBatch = new ArrayList<List<Message>>();
        
        // 第一个批次：询问天气
        var messages1 = new ArrayList<Message>();
        messages1.add(Message.builder()
                .role(Message.ROLE.SYSTEM)
                .type(Message.TYPE.TEXT)
                .text("你是一个乐于助人的助手。")
                .build());
        messages1.add(Message.builder()
                .role(Message.ROLE.USER)
                .type(Message.TYPE.TEXT)
                .text("巴黎的天气怎么样？给出一个随机温度。")
                .build());
        messageBatch.add(messages1);
        
        // 第二个批次：询问天气
        var messages2 = new ArrayList<Message>();
        messages2.add(Message.builder()
                .role(Message.ROLE.SYSTEM)
                .type(Message.TYPE.TEXT)
                .text("你是一个乐于助人的助手。")
                .build());
        messages2.add(Message.builder()
                .role(Message.ROLE.USER)
                .type(Message.TYPE.TEXT)
                .text("北京的天气怎么样？给出一个随机温度。")
                .build());
        messageBatch.add(messages2);
        
        return messageBatch;
    }

    @Test
    void testBatch() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            // 创建多个消息批次
            var messageBatch = createWeatherMessageBatch();
            
            // 调用 batch 方法，使用 WeatherResponse 输出格式
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);
            
            // 验证响应不为空
            assertNotNull(batchId);
            assertFalse(batchId.isEmpty());
            
            // 打印响应以便调试
            System.out.println("Batch ID: " + batchId);
        });
    }

    @Test
    void testTaskStatus() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建多个消息批次
            var messageBatch = createWeatherMessageBatch();
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);
            
            // 查询任务状态
            var taskStatus = getLlm("batch-test-model").taskStatus(batchId);
            
            // 验证任务状态不为空
            assertNotNull(taskStatus);
            assertNotNull(taskStatus.getId());
            assertNotNull(taskStatus.getStatus());
            
            // 验证状态是有效的
            assertTrue(
                BatchTaskInfo.STATUS.DOING.equals(taskStatus.getStatus()) ||
                BatchTaskInfo.STATUS.DONE.equals(taskStatus.getStatus()) ||
                BatchTaskInfo.STATUS.ERROR.equals(taskStatus.getStatus()) ||
                BatchTaskInfo.STATUS.EXPIRED.equals(taskStatus.getStatus()) ||
                BatchTaskInfo.STATUS.CANCELLED.equals(taskStatus.getStatus())
            );
            
            // 打印任务状态以便调试
            System.out.println("Task status: " + taskStatus);
        });
    }

    @Test
    void testTaskResult() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            // 创建多个消息批次
            var messageBatch = createWeatherMessageBatch();
            var batchId = getLlm("batch-test-model").batch(messageBatch, WeatherResponse.class);


            // 等待任务完成（批处理可能需要一些时间）
            BatchTaskInfo batchTaskInfo;
            int maxRetries = 12; // 最多等待60秒 (12 * 5秒)
            int retryCount = 0;
            
            do {
                batchTaskInfo = getLlm("batch-test-model").taskStatus(batchId);
                if (BatchTaskInfo.STATUS.DONE.equals(batchTaskInfo.getStatus())) {
                    break;
                }

                Thread.sleep(5000); // 等待5秒后重试
                retryCount++;
            } while (retryCount < maxRetries);
            
            if (retryCount >= maxRetries) {
                throw new RuntimeException("Batch task did not complete within timeout period");
            }
            
            // 验证任务状态
            assertNotNull(batchTaskInfo);
            assertNotNull(batchTaskInfo.getSuccessResultId());
            
            // 获取任务结果
            var results = getLlm("batch-test-model").taskResult(batchTaskInfo);
            
            // 验证结果不为空
            assertNotNull(results);
            assertFalse(results.isEmpty());
            assertEquals(2, results.size()); // 应该有两个结果，对应两个请求
            
            // 验证结果内容
            for (var result : results) {
                assertNotNull(result);
                if (result.getChoices() != null && !result.getChoices().isEmpty()) {
                    var choice = result.getChoices().getFirst();
                    if (choice.getText() != null) {
                        assertTrue(choice.getText().length() > 0);
                    }
                }
            }
            
            // 打印结果以便调试
            System.out.println("Task results: " + results);
        });
    }

    @Test
    void testQuery() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var messages = new ArrayList<Message>();
            messages.add(Message.builder()
                    .role(Message.ROLE.SYSTEM)
                    .type(Message.TYPE.TEXT)
                    .text("你是一个乐于助人的助手。")
                    .build());
            messages.add(Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("1+1等于几？")
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
    void testBuildToolsJson() {
        var subParam1 = new Tool.Parameter("street", "string", "街道地址", true, null);
        var subParam2 = new Tool.Parameter("city", "string", "城市名称", true, null);
        var subParam3 = new Tool.Parameter("zipcode", "string", "邮政编码", false, null);

        var addressParam = new Tool.Parameter("address", "object", "详细地址信息", true, List.of(subParam1, subParam2, subParam3));
        var tool = Tool.<Map>builder()
                .name("search_location")
                .description("根据地址搜索地理位置")
                .parameters(List.of(addressParam))
                .build();

        var toolsJson = llm.buildToolsJson(List.of(tool));

        assertNotNull(toolsJson);
        assertEquals(1, toolsJson.size());

        var toolNode = toolsJson.get(0);
        var function = toolNode.path("function");
        var parameters = function.path("parameters");

        var properties = parameters.path("properties");
        var addressProperty = properties.path("address");
        assertEquals("object", addressProperty.path("type").asText());
        assertEquals("详细地址信息", addressProperty.path("description").asText());

        var addressProperties = addressProperty.path("properties");
        assertNotNull(addressProperties.path("street"));
        assertEquals("string", addressProperties.path("street").path("type").asText());
        assertEquals("街道地址", addressProperties.path("street").path("description").asText());

        assertNotNull(addressProperties.path("city"));
        assertEquals("string", addressProperties.path("city").path("type").asText());
        assertEquals("城市名称", addressProperties.path("city").path("description").asText());

        assertNotNull(addressProperties.path("zipcode"));
        assertEquals("string", addressProperties.path("zipcode").path("type").asText());
        assertEquals("邮政编码", addressProperties.path("zipcode").path("description").asText());

        var addressRequired = addressProperty.path("required");
        assertTrue(addressRequired.isArray());
        assertEquals(2, addressRequired.size());
    }
}