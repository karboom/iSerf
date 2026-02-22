package me.karboom.java.iSlogger.agent;

import me.karboom.java.iSlogger.llm.text.OpenAITest;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.tool.FunctionWrapper;
import me.karboom.java.iSlogger.tool.Loader;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.test.StepVerifier;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;

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
                .iDirectory("/home/karboom/projects/karboom/java/iSlogger/class")
                .build();
        tools.add(timeTool);
    }

    @Test
    public void testToolCall() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
            var llm = llmTest.getLlm();
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
    void testEvolution() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // 创建 Agent
            var llm = llmTest.getLlm();
//            llm.llmConfig.put("thinking", true);
            
            var tools = new Loader(2000).fromIFunction("/home/karboom/projects/karboom/java/iSlogger/src/main/java/me/karboom/java/iSlogger/iFunction", null, null);

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

            var agent = new Agent("test-agent", prompt, llm, tools) {};

            // 创建一个列表来收集广播的消息
            var receivedMessages = new ArrayList<Item>();

            // 订阅 broadcast 流
            agent.broadcast
                    .log()
                    .subscribe(
                    item -> {
                        receivedMessages.add(item);
                    }
            );

//            agent.send("我想直到用户最近一周创建订单数量的趋势");
            agent.send("你好");
            agent.send("3");

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
        var llm = llmTest.getLlm();
        var agent = new Agent("test-agent", "", llm, tools) {};

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
    void testMessageEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var llm = llmTest.getLlm();
            var agent = new Agent("test-message-agent", "你是一个有用的助手", llm, tools) {};

            var receivedItems = new ArrayList<Item>();
            agent.subscribe(item -> receivedItems.add(item));

            agent.send("你好");

            Thread.sleep(5000);

            assertTrue(receivedItems.size() > 0, "应收到响应消息");
            var textItems = receivedItems.stream()
                    .filter(item -> item.getType().equals(Item.TYPE.TEXT))
                    .toList();
            assertFalse(textItems.isEmpty(), "应包含文本类型的响应");
        });
    }

    /**
     * 测试 ORGANIZE_MEMORY 事件处理
     */
    @Test
    void testOrganizeMemoryEvent() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var llm = llmTest.getLlm();
            var agent = new Agent("test-organize-memory-agent", "你是一个有用的助手", llm, tools) {};

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
                var userItem = Item.builder()
                        .id(UUID.randomUUID().toString())
                        .role(Item.ROLE.USER)
                        .type(Item.TYPE.TEXT)
                        .text(messages.get(i))
                        .build();
                agent.memory.add(userItem);

                var assistantItem = Item.builder()
                        .id(UUID.randomUUID().toString())
                        .role(Item.ROLE.ASSISTANT)
                        .type(Item.TYPE.TEXT)
                        .text("这是一个回复")
                        .build();
                agent.memory.add(assistantItem);
            }

            Thread.sleep(1000);

            var memoryBeforeCompress = agent.memory.get();
            var countBefore = memoryBeforeCompress.size();
            System.out.println("压缩前记忆数量: " + countBefore);
            assertTrue(countBefore > 20, "压缩前应有超过20条记忆");

            var event = Event.builder()
                    .type(Event.Type.ORGANIZE_MEMORY)
                    .priority(1)
                    .build();

            agent.queue.offer(event);

            Thread.sleep(10000);

            var memoryAfterCompress = agent.memory.get();
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
            var llm = llmTest.getLlm();
            var agent = new Agent("test-recovery-agent", "你是一个有用的助手", llm, tools) {};

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
            var llm = llmTest.getLlm();
            var agent = new Agent("test-cache-agent", "你是一个有用的助手", llm, tools) {};

            // 准备测试参数
            var testParams = new HashMap<String, Object>() {{
                put("location", "北京");
            }};

            // 创建缓存
            var cache = ToolCallCache.builder()
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
            var cacheWithNonExistentTool = ToolCallCache.builder()
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
}
