package me.karboom.java.iSerf.server.protocol;

import io.socket.client.IO;
import io.socket.client.Socket;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Websocket 性能测试：单节点服务器 + 1 万客户端连接
 */
class WebsocketPerformanceTest {

    public IMessageBus messageBus = new Pulsar("pulsar://localhost:6650");
    public IMetaData metaData = new RedisSingle("redis://localhost:6379");

    private static final int AGENT_COUNT = 10000;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 9098;
    private static final String SIMULATED_DATA_FILE = "src/test/resources/memory/test/memory.json";
    private static final int CHARS_PER_ROUND = 400;
    private static final int ROUND_COUNT = 500;

    /**
     * 生成模拟对话文本
     */
    private String generateSimulatedText(String[] templates, int round, int charCount) {
        var builder = new StringBuilder();
        var counter = 0;
        while (builder.length() < charCount) {
            var template = templates[counter % templates.length];
            var topic = "话题" + (counter + round * 10);
            builder.append(template.formatted(topic));
            counter++;
        }

        return builder.substring(0, Math.min(builder.length(), charCount));
    }

    /**
     * 生成模拟对话 JSON 文件
     * 用于后续测试从文件加载数据
     */
    @Test
    void generateSimulatedJsonFile() {
        System.out.println(" generateSimulatedJsonFile 开始生成模拟对话 JSON 文件");

        var items = JSONUtil.createArray();

        var templates = new String[]{
                "你好，我想了解一下关于%s的话题。",
                "请问你能帮我解释一下%s吗？",
                "我对%s很感兴趣，能详细介绍一下吗？",
                "关于%s，我有一些问题想问。",
                "我觉得%s很有意思，你觉得呢？",
                "我在学习%s，遇到了一些困难。",
                "听说%s很重要，是真的吗？",
                "我想深入了解%s的相关知识。",
                "对于%s，你有什么建议吗？",
                "能给我讲讲%s的历史吗？"
        };

        for (var round = 0; round < ROUND_COUNT; round++) {
            var userText = generateSimulatedText(templates, round, CHARS_PER_ROUND / 2);
            var assistantText = generateSimulatedText(templates, round + 1000, CHARS_PER_ROUND / 2);

            var userItem = JSONUtil.create()
                    .put("id", DataUtil.getFlakeId())
                    .put("role", Message.ROLE.USER)
                    .put("type", Message.TYPE.TEXT)
                    .put("text", userText)
                    .put("isForgotten", 0);
            items.add(userItem);

            var assistantItem = JSONUtil.create()
                    .put("id", DataUtil.getFlakeId())
                    .put("role", Message.ROLE.ASSISTANT)
                    .put("type", Message.TYPE.TEXT)
                    .put("text", assistantText)
                    .put("isForgotten", 0);
            items.add(assistantItem);
        }

        try {
            var dir = new File("src/test/resources");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            Files.writeString(Paths.get(SIMULATED_DATA_FILE), JSONUtil.stringify(items));
            System.out.println(" generateSimulatedJsonFile 文件已生成：" + SIMULATED_DATA_FILE);
        } catch (IOException e) {
            throw new RuntimeException("生成 JSON 文件失败", e);
        }
    }

    /**
     * 启动单节点服务器
     * 用于 profile 服务器内存占用
     */
    @Test
    void startServer() {
        assertTimeoutPreemptively(Duration.ofMinutes(60), () -> {
            var server = new TestWebsocketServer(SERVER_IP, SERVER_PORT, messageBus, metaData);
            server.start();

            System.out.println("===========================================");
            System.out.println("服务器已启动，端口：" + SERVER_PORT);
            System.out.println("服务器 ID: " + server.id);
            System.out.println("请在 profiler 中查看服务器内存占用");
            System.out.println("服务器进程将保持运行 60 分钟，供 profile 使用");
            System.out.println("===========================================");

            // 保持服务器运行 60 分钟供 profile 使用
            Thread.sleep(60 * 60 * 1000);

            // 停止服务器
            server.stop();
            System.out.println("服务器已停止");
        });
    }

    /**
     * 创建 1 万个客户端连接，每个连接创建一个 agent
     */
    @Test
    void createClients() {
        assertTimeoutPreemptively(Duration.ofMinutes(30), () -> {

            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(AGENT_COUNT * 2);
            dispatcher.setMaxRequestsPerHost(AGENT_COUNT * 2);

            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .connectionPool(new ConnectionPool(AGENT_COUNT*2, 1, TimeUnit.MINUTES))
                    .dispatcher(dispatcher)
                    .readTimeout(1, TimeUnit.MINUTES) // important for HTTP long-polling
                    .build();

            var options = new IO.Options();
            options.transports = new String[]{"websocket"};
            options.reconnection = false;
            options.forceNew = true;

            options.callFactory = okHttpClient;
            options.webSocketFactory = okHttpClient;

            var socketUrl = "http://" + SERVER_IP + ":" + SERVER_PORT + "/user";

            System.out.println("===========================================");
            System.out.println("开始创建 " + AGENT_COUNT + " 个客户端连接...");
            System.out.println("请在 profiler 中查看客户端内存占用");
            System.out.println("===========================================");

            Socket[] sockets = new Socket[AGENT_COUNT];
            AtomicReferenceArray<String> agentIds = new AtomicReferenceArray<>(AGENT_COUNT);
            AtomicInteger connectedCount = new AtomicInteger(0);
            AtomicInteger createdCount = new AtomicInteger(0);

            try {
                var createStartTime = System.currentTimeMillis();

                CountDownLatch connectLatch = new CountDownLatch(AGENT_COUNT);
                CountDownLatch createAgentLatch = new CountDownLatch(AGENT_COUNT);

                for (var i = 0; i < AGENT_COUNT; i++) {
                    final int index = i;
                    sockets[i] = IO.socket(socketUrl, options);

                    sockets[i].on(Socket.EVENT_CONNECT, args -> {
                        connectedCount.incrementAndGet();
                        connectLatch.countDown();

//                        sockets[index].emit("agent.create", JSONUtil.stringify(Map.of("prompt", "测试 Agent " + index)), new io.socket.client.Ack() {
//                            @Override
//                            public void call(Object... args) {
//                                if (args.length > 0 && args[0] instanceof String) {
//                                    var response = JSONUtil.parse((String) args[0]);
//                                    agentIds.set(index, response.path("agentId").asText());
//                                    createdCount.incrementAndGet();
//                                    createAgentLatch.countDown();
//                                }
//                            }
//                        });
                    });

                    sockets[i].on(Socket.EVENT_CONNECT_ERROR, args -> {
                        System.out.println("连接错误 " + index + ": " + (args.length > 0 ? args[0] : "未知错误"));
                        connectLatch.countDown();
                    });

                    sockets[i].connect();


                }

                assertTrue(connectLatch.await(120, TimeUnit.SECONDS), "所有客户端应成功连接");
                System.out.println("连接完成，耗时：" + (System.currentTimeMillis() - createStartTime) + "ms, 连接数：" + connectedCount.get());

                assertTrue(createAgentLatch.await(120, TimeUnit.SECONDS), "所有 agent 应成功创建");
                var createTime = System.currentTimeMillis() - createStartTime;
                System.out.println("创建 " + AGENT_COUNT + " 个 agent 耗时：" + createTime + "ms");

                for (var i = 0; i < AGENT_COUNT; i++) {
                    assertNotNull(agentIds.get(i), "第 " + i + " 个 agentId 不应为空");
                }

                var totalTime = System.currentTimeMillis() - createStartTime;
                System.out.println("===========================================");
                System.out.println("客户端测试结果:");
                System.out.println("  客户端总数：" + AGENT_COUNT);
                System.out.println("  连接成功数：" + connectedCount.get());
                System.out.println("  创建成功数：" + createdCount.get());
                System.out.println("  总耗时：" + totalTime + "ms");
                System.out.println("  平均每个客户端耗时：" + (totalTime / AGENT_COUNT) + "ms");
                System.out.println("===========================================");

                assertEquals(AGENT_COUNT, connectedCount.get(), "所有客户端都应成功连接");
                assertEquals(AGENT_COUNT, createdCount.get(), "所有 agent 都应成功创建");

                Thread.sleep(1000 * 3600);

            } finally {
                System.out.println("开始关闭客户端连接...");
                for (var i = 0; i < AGENT_COUNT; i++) {
                    if (sockets[i] != null && sockets[i].connected()) {
                        sockets[i].disconnect();
                    }
                }
                System.out.println("客户端连接已关闭");
            }
        });
    }

    static class TestWebsocketServer extends me.karboom.java.iSerf.server.protocol.Websocket {

        @Override
        public Agent createAgent(ObjectNode params) {
            var llm = new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"), "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
            var llmProvider = new FixedLlmProvider(llm);
            var agent = new Agent(UUID.randomUUID().toString(), "随便输出点啥，测试一下", llmProvider, null);

            loadConversationFromFile(agent);

            return agent;
        }

        /**
         * 从 JSON 文件加载对话到 memory
         */
        private void loadConversationFromFile(Agent agent) {
            System.out.println(" loadConversationFromFile 开始从文件加载对话数据");

            try {
                var fileContent = Files.readString(Paths.get(SIMULATED_DATA_FILE));
                var items = JSONUtil.parse(fileContent);

                if (items.isMissingNode() || items.isNull() || !items.isArray()) {
                    System.out.println(" loadConversationFromFile 文件内容不是数组格式");
                    return;
                }

                var counter = 0;
                for (var i = 0; i < items.size(); i++) {
                    var item = items.get(i);

                    var id = item.path("id").asText();
                    var role = item.path("role").asText();
                    var type = item.path("type").asText();
                    var text = item.path("text").asText();
                    var isForgotten = item.path("isForgotten").asInt();

                    agent.getMemoryManager().add(Message.builder()
                            .id(id.isEmpty() ? DataUtil.getFlakeId() : id)
                            .role(role)
                            .type(type)
                            .text(text)
                            .isForgotten(isForgotten)
                            .build());
                    counter++;
                }

                System.out.println(" loadConversationFromFile 已加载 " + counter + " 条对话记录");

            } catch (IOException e) {
                throw new RuntimeException("读取 JSON 文件失败：" + SIMULATED_DATA_FILE, e);
            }
        }

        public TestWebsocketServer(String ip, Integer port, IMessageBus messageBus, IMetaData metaData) {
            super(ip, port, messageBus, metaData);
        }
    }
}