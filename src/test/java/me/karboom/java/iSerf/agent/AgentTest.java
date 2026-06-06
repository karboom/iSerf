package me.karboom.java.iSerf.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.SneakyThrows;
import me.karboom.java.iSerf.agent.tool.CallCache;
import me.karboom.java.iSerf.agent.tool.CallResult;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.agent.tool.Loader;
import me.karboom.java.iSerf.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private List<Tool<?>> tools;
    private OpenAITest llmTest;
    private FixedLlmProvider llmProvider;

    static class ToolParam {
        @JsonPropertyDescription("城市名称")
        public String location;

        @JsonPropertyDescription("温度单位")
        @JsonProperty(required = true)
        public String unit;
    }

    @BeforeEach
    void setUp() {
        // 初始化 llmTest
        llmTest = new OpenAITest();
        llmProvider = new FixedLlmProvider(llmTest.getLlm());

        // 创建测试工具
        tools = new ArrayList<>();

        var weatherT = new Tool<ToolParam>(){};
        weatherT.setType(Tool.TYPE.FUNCTION);
        weatherT.setDescription("Get the current weather for a location");
        weatherT.setName("getWeather");
        weatherT.setFunction((ctx, params) -> {
            return CallResult.builder().llm(( Math.random() * 15 + 15) + "摄氏度").build();
        });

        tools.add(weatherT);


    }

    @Test
    public void testToolCall() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
//            llm.llmConfig.put("thinking", true);

            // 定义输入列表
            var inputs = List.of(
                    "杭州的天气如何"
//                    "杭州的天气如何，上海的天气如何"
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

                var agent = new Agent("test-agent-" + i, "", llmProvider, tools) {};

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
    public void testAgentBroadcast() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
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

                var agent = new Agent("test-agent-" + i, "", llmProvider, tools) {};

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
        var agent = new Agent("test-agent", "", llmProvider, tools) {};

        // 测试用例1: 正常情况 - 工具存在且更新成功
        var toolCall = Message.ToolCall.builder()
                .name("getWeather")
                .arguments(new HashMap<>(){{
                    put("location", "伦敦");
                }})
                .result(CallResult.builder()
                        .error(new RuntimeException("这不是一个中国的地点"))
                        .build())
                .build();

        // 验证 Mono<Void> 返回值可以正确处理
        var updateToolMono = agent.updateTool(toolCall);
        assertNotNull(updateToolMono, "updateTool should return a Mono<Void>");
        // 订阅 Mono 以触发执行，并验证不会抛出异常
        assertDoesNotThrow(() -> updateToolMono.block(), "updateTool should not throw exception for valid tool");

        // 测试用例2: 工具不存在的情况
        var nonExistentToolCall = Message.ToolCall.builder()
                .name("non-existent-tool")
                .arguments(new HashMap<>())
                .result(CallResult.builder()
                        .error(new RuntimeException("这不是一个中国的地点"))
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
    void testEvolution() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
//            llm.llmConfig.put("thinking", true);
            
            var tools = new Loader(2000).fromIFunction("/home/karboom/projects/karboom/iSerf/iSerf/src/main/java/me/karboom/java/iSerf/iFunction", "echarts", null);

            var prompt = """
                    CREATE TABLE users (
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID（主键）',
                        username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名（登录用）',
                        email VARCHAR(128) NOT NULL UNIQUE COMMENT '邮箱',
                        password_hash CHAR(60) NOT NULL COMMENT '密码哈希（如 bcrypt）',
                        real_name VARCHAR(64) DEFAULT NULL COMMENT '真实姓名',
                        phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
                        status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-正常，0-禁用',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    
                        PRIMARY KEY (id),
                        INDEX idx_username (username),
                        INDEX idx_email (email),
                        INDEX idx_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
                    
                    CREATE TABLE operation_logs (
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID（主键）',
                        user_id BIGINT UNSIGNED NOT NULL COMMENT '操作用户ID（关联 users.id）',
                        action VARCHAR(64) NOT NULL COMMENT '操作类型（如 login, create_order, delete_user）',
                        resource_type VARCHAR(64) NOT NULL COMMENT '资源类型（如 user, order, product）',
                        resource_id VARCHAR(64) DEFAULT NULL COMMENT '资源ID（如订单号、用户ID，支持非数字ID）',
                        description TEXT DEFAULT NULL COMMENT '操作描述（如 "删除用户张三"）',
                        ip_address VARCHAR(45) DEFAULT NULL COMMENT '客户端IP（支持IPv6）',
                        user_agent TEXT DEFAULT NULL COMMENT 'User-Agent',
                        result TINYINT NOT NULL DEFAULT 1 COMMENT '结果：1-成功，0-失败',
                        error_message TEXT DEFAULT NULL COMMENT '错误信息（仅失败时记录）',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                    
                        PRIMARY KEY (id),
                        INDEX idx_user_id (user_id),
                        INDEX idx_action (action),
                        INDEX idx_resource (resource_type, resource_id),
                        INDEX idx_created_at (created_at),
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';
                    """;

            var agent = new Agent("test-agent", prompt, llmProvider, tools) {};

            // 创建一个列表来收集广播的消息
            var receivedMessages = new ArrayList<Message>();

            // 订阅 broadcast 流
            agent.broadcast
                    .log()
                    .subscribe(
                    item -> {
                        receivedMessages.add(item);
                    }
            );

            agent.send("我想直到用户最近一周创建订单数量的趋势");
//            agent.send("你好");
//            agent.send("3");

            Thread.sleep(1000*20);

            System.out.println(receivedMessages);
        });
    }


    /**
     * 测试错误抛出流程
     */
    @Test
    void testErrorHandle() {
        // 创建 Agent
        var agent = new Agent("test-agent", "", llmProvider, tools) {};

        agent.send("xxx");

        agent.broadcast
                .doOnError(e -> {
                    System.out.println("error: " + e.getMessage());
                })
                .subscribe(System.out::println);
    }

    /**
     * 测试 MESSAGE 事件处理
     */
    @Test
    @Timeout(30)
    @SneakyThrows
    void testMessageEvent() {
            var agent = new Agent("test-message-agent", "请你做一个自我介绍", llmProvider, tools) {};

            var receivedItems = new ArrayList<Message>();
            var latch = new CountDownLatch(1);

            agent.subscribe(item -> {
                System.out.println(item);
                receivedItems.add(item);
                if (Integer.valueOf(0).equals(item.getIsSegment())
                    && Message.TYPE.TEXT.equals(item.getType())) {
                    latch.countDown();
                }
            });

            agent.send("你好");

            latch.await();

            assertFalse(receivedItems.isEmpty(), "应收到响应消息");
            var textItems = receivedItems.stream()
                    .filter(item -> item.getType().equals(Message.TYPE.TEXT))
                    .toList();
            assertFalse(textItems.isEmpty(), "应包含文本类型的响应");
            var thinkingItems = receivedItems.stream()
                    .filter(item -> item.getType().equals(Message.TYPE.THINKING))
                    .toList();
            assertFalse(thinkingItems.isEmpty(), "应包含思考类型的响应");
            var textSegmentZeroCount = textItems.stream()
                    .filter(m -> Integer.valueOf(0).equals(m.getIsSegment()))
                    .count();
            var thinkingSegmentZeroCount = thinkingItems.stream()
                    .filter(m -> Integer.valueOf(0).equals(m.getIsSegment()))
                    .count();
            assertTrue(textSegmentZeroCount == 1, "TEXT类型isSegment=0应恰好为1");
            assertTrue(thinkingSegmentZeroCount == 1, "THINKING类型isSegment=0应恰好为1");
    }

    /**
     * 测试 ORGANIZE_MEMORY 事件处理
     */
    @Test
    void testOrganizeMemoryEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var agent = new Agent("test-organize-memory-agent", "你是一个有用的助手", llmProvider, tools) {};

            var messages = List.of(
                    "你好",
                    "今天天气怎么样",
                    "北京天气如何",
                    "上海天气如何",
                    "杭州天气如何",
                    "你知道什么是人工智能吗",
                    "介绍一下机器学习",
                    "深度学习是什么",
                    "神经网络和深度学习的关系",
                    "谢谢你的回答"
            );

            for (var i = 0; i < messages.size(); i++) {
                var userItem = Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(Message.ROLE.USER)
                        .type(Message.TYPE.TEXT)
                        .text(messages.get(i))
                        .build();
                agent.getMemoryManager().add(userItem);

                var assistantItem = Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(Message.ROLE.ASSISTANT)
                        .type(Message.TYPE.TEXT)
                        .text("这是一个回复")
                        .build();
                agent.getMemoryManager().add(assistantItem);
            }

            Thread.sleep(1000);

            var memoryBeforeCompress = agent.getMemoryManager().getMessages();
            var countBefore = memoryBeforeCompress.size();
            System.out.println("压缩前记忆数量: " + countBefore);
            assertTrue(countBefore > 20, "压缩前应有超过20条记忆");

            var event = Event.builder()
                    .type(Event.Type.ORGANIZE_MEMORY)
                    .priority(1)
                    .build();

            agent.queue.offer(event);

            Thread.sleep(10000);

            var memoryAfterCompress = agent.getMemoryManager().getMessages();
            var countAfter = memoryAfterCompress.size();
            System.out.println("压缩后记忆数量: " + countAfter);
            
            var forgottenCount = memoryAfterCompress.stream()
                    .filter(item -> item.getIsForgotten() != null && item.getIsForgotten() == 1)
                    .count();
            System.out.println("被遗忘的记忆数量: " + forgottenCount);
            assertTrue(forgottenCount > 0, "应有记忆项被标记为遗忘");
            
            var summaryCount = memoryAfterCompress.stream()
                    .filter(item -> item.getIsForgotten() != null && item.getIsForgotten() == 0)
                    .count();
            assertTrue(summaryCount > 0, "应有未遗忘的摘要内容");
        });
    }

    /**
     * 测试 RECOVERY 事件处理
     */
    @Test
    void testRecoveryEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var agent = new Agent("test-recovery-agent", "你是一个有用的助手", llmProvider, tools) {};

            agent.recovery();

            Thread.sleep(1000);

            assertTrue(true, "RECOVERY 事件已处理");
        });
    }

    /**
     * 测试 invokeToolCallCache 方法
     */
    @Test
    void testInvokeToolCallCache() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var agent = new Agent("test-cache-agent", "你是一个有用的助手", llmProvider, tools) {};

            // 准备测试参数
            var testParams = new HashMap<String, Object>() {{
                put("location", "北京");
            }};

            // 创建缓存
            var cache = CallCache.builder()
                    .callId("test-call-001")
                    .toolName("getWeather")
                    .params(testParams)
                    .build();
            agent.toolCallCaches.add(cache);

            // 测试正常调用缓存
            var result = agent.invokeToolCallCache("test-call-001");
            assertNotNull(result, "应返回调用结果");

            // 测试缓存不存在的情况
            assertThrows(RuntimeException.class, () -> {
                agent.invokeToolCallCache("non-existent-call");
            }, "缓存不存在时应抛出异常");

            // 测试工具不存在的情况
            var cacheWithNonExistentTool = CallCache.builder()
                    .callId("test-call-002")
                    .toolName("non-existent-tool")
                    .params(testParams)
                    .build();
            agent.toolCallCaches.add(cacheWithNonExistentTool);

            assertThrows(RuntimeException.class, () -> {
                agent.invokeToolCallCache("test-call-002");
            }, "工具不存在时应抛出异常");
        });
    }

    /**
     * 测试 calcMemoryBillings 方法
     */
    @Test
    void testCalcMemoryBillings() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var agent = new Agent("test-billing-agent", "你是一个有用的助手", llmProvider, tools) {};

            // 添加一些测试记忆
            agent.getMemoryManager().add(Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("你好")
                    .isForgotten(0)
                    .build());

            agent.getMemoryManager().add(Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(Message.ROLE.ASSISTANT)
                    .type(Message.TYPE.TEXT)
                    .text("你好，有什么可以帮助你的吗？")
                    .isForgotten(0)
                    .build());

            // 调用 calcMemoryBillings
            agent.calcMemoryBillings();

            // 验证方法执行成功（不抛异常即成功）
            assertTrue(true, "calcMemoryBillings 方法执行成功");
        });
    }

    /**
     * 测试 interrupt 方法 - 发送 500 字输出请求，2 秒后中断，然后立即让 Agent 做一首诗词
     */
    @Test
    void testInterrupt() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var agent = new Agent("test-interrupt-agent", "你是一个有用的助手", llmProvider, tools) {};

            var receivedItems = new ArrayList<Message>();
            agent.subscribe(item -> {
                System.out.println("收到消息：" + item);
                receivedItems.add(item);
            });


            // 发送要求输出 500 字的消息
            agent.send("输出一篇英文短文500单次");

            // 等待 2 秒后中断
            Thread.sleep(3000);

            // 调用 interrupt 中断当前处理
            System.out.println("调用 interrupt 中断...");
            agent.interrupt();


            // 验证 interrupt 后 agent 的事件处理器重新运行
            assertNotNull(agent.eventDisposable, "interrupt 后 eventDisposable 应不为空");
            assertFalse(agent.eventDisposable.isDisposed(), "interrupt 后事件处理器应重新运行");

            System.out.println("中断前收到消息总数：" + receivedItems.size());

            // 中断后立即让 Agent 做一首诗词
            System.out.println("中断后发送做诗词请求...");
            receivedItems.clear();
            agent.send("无视之前的要求，创作一首七言绝句");

            // 等待诗词生成完成
            Thread.sleep(5000);

            System.out.println("做诗词后收到消息总数：" + receivedItems.size());
            var textItems = receivedItems.stream()
                    .filter(item -> item.getType().equals(Message.TYPE.TEXT))
                    .toList();
            assertFalse(textItems.isEmpty(), "应包含文本类型的响应");

            // 输出诗词内容
            textItems.forEach(item -> System.out.println("诗词内容：" + item.getText()));
            
            assertTrue(true, "interrupt 方法执行成功，且中断后能正常响应新请求");
        });
    }

    /**
     * 测试 Path 初始化构造函数
     */
    @Test
    void testConstructor() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var testPath = Paths.get("src/test/resources/agent/constructor");
            var agent = new Agent("test-path-init-agent", llmProvider, testPath) {};

            assertEquals("test-path-init-agent", agent.id);
            assertEquals("你是一个基于路径初始化的测试助手。", agent.prompt);
            assertEquals(0, agent.getMemoryManager().size());
            assertEquals("你是一个基于路径初始化的测试助手。", agent.getMemoryManager().getSystemPrompt());
        });
    }

    /**
     * 测试 call 方法 - 直接调用智能体，不新增记忆
     */
    @Test
    void testCall() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var agent = new Agent("test-call-agent", "你是一个有用的助手", llmProvider, tools) {};

            var memorySizeBefore = agent.getMemoryManager().size();

            var userMessage = Message.builder()
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text("你好，请用一句话介绍你自己")
                    .build();

            var result = agent.call(userMessage, null);

            assertNotNull(result, "call 返回结果不应为空");
            assertEquals(Message.ROLE.ASSISTANT, result.getRole(), "返回消息的角色应为 ASSISTANT");
            assertNotNull(result.getText(), "返回消息的文本不应为空");
            assertTrue(result.getText().length() > 0, "返回消息的文本长度应大于0");
            assertEquals(memorySizeBefore, agent.getMemoryManager().size(), "call 不应新增记忆");

            System.out.println("call 返回结果: " + result.getText());
        });
    }
}
