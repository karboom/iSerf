package me.karboom.java.iSerf.server;

import io.socket.client.IO;
import io.socket.client.Socket;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.llm.text.OpenAITest;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.server.protocol.Websocket;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;


class WebsocketTest {
    public OpenAITest llm = new OpenAITest();

    public IMessageBus messageBus = new Pulsar("pulsar://localhost:6650");
    public IMetaData metaData =new RedisSingle("redis://localhost:6379");


    /**
     * 针对元数据更新情况进行测试
     * 1. 创建一个 server，检查数据
     * 2. 创建第二个 server，检查数据
     * 3. 删除一个 server，检查数据
     */
    @Test
    void testMetaData() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var server1 = new TestWebsocketServer("127.0.0.1", 9096, messageBus, metaData);
            server1.start();

            // 1. 创建第一个 server 后，检查 server1 的节点 ID 存在
            Thread.sleep(1000);
            var nodesAfterFirst = metaData.getNodes();
            var server1IdExists = nodesAfterFirst.stream().anyMatch(n -> server1.id.equals(n.id));
            assertTrue(server1IdExists, "创建第一个 server 后，server1 的节点 ID 应存在");

            var server2 = new TestWebsocketServer("127.0.0.1", 9097, messageBus, metaData);
            server2.start();

            // 2. 创建第二个 server 后，检查 server2 的节点 ID 存在
            Thread.sleep(1000);
            var nodesAfterSecond = metaData.getNodes();
            var server2IdExists = nodesAfterSecond.stream().anyMatch(n -> server2.id.equals(n.id));
            assertTrue(server2IdExists, "创建第二个 server 后，server2 的节点 ID 应存在");

            // 3. 删除第一个 server 后，检查 server1 的节点 ID 消失
            server1.stop();
            Thread.sleep(1000);
            var nodesAfterRemove = metaData.getNodes();
            var server1IdExistsAfterRemove = nodesAfterRemove.stream().anyMatch(n -> server1.id.equals(n.id));
            assertFalse(server1IdExistsAfterRemove, "删除第一个 server 后，server1 的节点 ID 应消失");

            server2.stop();
        });
    }

    /**
     * 创建两个 server，其中一个 agent/create，另一个 agent/active，然后 agent/send
     */
    @Test
    void testMessageBus() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var server1 = new TestWebsocketServer("127.0.0.1", 9094, messageBus, metaData);
            var server2 = new TestWebsocketServer("127.0.0.1", 9095, messageBus, metaData);
            server1.start();
            server2.start();

            try {
                var options = new IO.Options();
                options.transports = new String[]{"websocket"};
                options.reconnection = false;

                // 连接到 server1 (9094) 创建 agent
                var socketUrl1 = "http://127.0.0.1:9094/user";
                var socket1 = IO.socket(socketUrl1, options);

                // 连接到 server2 (9095) 激活 agent 和 send
                var socketUrl2 = "http://127.0.0.1:9095/user";
                var socket2 = IO.socket(socketUrl2, options);

                var connectLatch1 = new CountDownLatch(1);
                var connectLatch2 = new CountDownLatch(1);
                var createLatch = new CountDownLatch(1);
                var activeLatch = new CountDownLatch(1);
                var sendLatch = new CountDownLatch(1);
                var messageLatch = new CountDownLatch(1);

                var agentIdRef = new AtomicReferenceArray<String>(1);
                var activeSuccessRef = new AtomicReferenceArray<Boolean>(1);
                var messages = new AtomicReferenceArray<tools.jackson.databind.node.ObjectNode>(1);

                // socket1 连接回调
                socket1.on(Socket.EVENT_CONNECT, args -> {
                    System.out.println("socket1 已连接");
                    connectLatch1.countDown();
                });

                // socket2 连接回调
                socket2.on(Socket.EVENT_CONNECT, args -> {
                    System.out.println("socket2 已连接");
                    connectLatch2.countDown();
                });

                // socket2 监听 message 事件
                socket2.on("agent/message", args -> {
                    if (args.length > 0 && args[0] instanceof String) {
                        var message = JSONUtil.parse((String) args[0]);
                        System.out.println("on Message " + message.toString());
                        messages.set(0, message);
                        messageLatch.countDown();
                    }
                });

                socket1.connect();
                socket2.connect();

                assertTrue(connectLatch1.await(5, TimeUnit.SECONDS), "socket1 应成功连接到 server1");
                assertTrue(connectLatch2.await(5, TimeUnit.SECONDS), "socket2 应成功连接到 server2");

                // 在 server1 上创建 agent
                socket1.emit("agent/create", JSONUtil.stringify(Map.of("prompt", "测试 EventProxy Agent")), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        System.out.println("agent/create 回调：" + args.length);
                        if (args.length > 0 && args[0] instanceof String) {
                            var response = JSONUtil.parse((String) args[0]);
                            agentIdRef.set(0, response.path("agentId").asText());
                            createLatch.countDown();
                        }
                    }
                });

                assertTrue(createLatch.await(10, TimeUnit.SECONDS), "agent 应成功创建");
                var agentId = agentIdRef.get(0);
                assertNotNull(agentId, "agentId 不应为空");
                System.out.println("创建的 agentId: " + agentId);

                // 在 server2 上激活 agent
                socket2.emit("agent/active", JSONUtil.stringify(Map.of("agentId", agentId)), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        System.out.println("agent/active 回调：" + args.length);
                        if (args.length > 0 && args[0] instanceof String) {
                            var response = JSONUtil.parse((String) args[0]);
                            activeSuccessRef.set(0, response.path("success").asBoolean());
                        }
                        activeLatch.countDown();
                    }
                });

//                assertTrue(activeLatch.await(10, TimeUnit.SECONDS), "agent 应成功激活");
//                assertTrue(activeSuccessRef.get(0), "agent/active 应返回 success=true");

                // 在 server2 上发送事件
                socket2.emit("agent/send", JSONUtil.stringify(Map.of("agentId", agentId, "event", Map.of("type", "test"))), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        System.out.println("agent/send 回调：" + args.length);
                        sendLatch.countDown();
                    }
                });

                assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "agent/send 应成功发送");

                // 验证收到 message 事件
                assertTrue(messageLatch.await(30, TimeUnit.SECONDS), "应收到 message 事件");
                var receivedMessage = messages.get(0);
                assertNotNull(receivedMessage, "message 事件应包含数据");
                assertEquals(agentId, receivedMessage.path("agentId").asText(), "message 事件应包含正确的 agentId");

            } finally {
                server1.stop();
                server2.stop();
            }
        });
    }



    @Test
    void testTwoNodeCluster() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var n = 1;
            var options = new IO.Options();
            options.transports = new String[]{"websocket"};
            options.reconnection = false;

            var socketUrl = "http://127.0.0.1:9092/user";
            var socket = IO.socket(socketUrl, options);

            var connectLatch = new CountDownLatch(1);
            var createLatch = new CountDownLatch(n);
            var activeLatch = new CountDownLatch(n);
            var messageLatch = new CountDownLatch(n);

            var agentIds = new AtomicReferenceArray<String>(n);
            var messages = new tools.jackson.databind.node.ObjectNode[n];

            socket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());

            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                System.out.println("连接错误：" + (args.length > 0 ? args[0] : "未知错误"));
                connectLatch.countDown();
            });

            socket.on("message", args -> {
                if (args.length > 0 && args[0] instanceof String) {

                    var message = JSONUtil.parse((String) args[0]);
                    System.out.println("on Message " + message.toString());
                    var agentId = message.path("agentId").asText();
                    for (int i = 0; i < n; i++) {
                        var storedId = agentIds.get(i);
                        if (storedId != null && storedId.equals(agentId)) {
                            messages[i] = message;
                            messageLatch.countDown();
                            break;
                        }
                    }
                }
            });

            socket.connect();

            assertTrue(connectLatch.await(1, TimeUnit.SECONDS), "应成功连接到服务器");

            for (int i = 0; i < n; i++) {
                final int index = i;
                socket.emit("agent/create", JSONUtil.stringify(Map.of("prompt", "测试 Agent " + i)), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        System.out.println(index + ": " + args.length);
                        if (args.length > 0 && args[0] instanceof String) {
                            var response = JSONUtil.parse((String) args[0]);
                            agentIds.set(index, response.get("agentId").asText());
                            createLatch.countDown();
                        }
                    }
                });
            }

            assertTrue(createLatch.await(10, TimeUnit.SECONDS), "n 个 Agent 应成功创建");
            for (int i = 0; i < n; i++) {
                assertNotNull(agentIds.get(i), "第 " + i + " 个 Agent 应返回 agentId");
            }

            for (int i = 0; i < n; i++) {
                final int index = i;
                socket.emit("agent/active", JSONUtil.stringify(Map.of("agentId", agentIds.get(i))), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        if (args.length > 0 && args[0] instanceof String) {
                            var response = JSONUtil.parse((String) args[0]);
                            assertTrue(response.path("success").asBoolean(), "激活第 " + index + " 个 Agent 应成功");
                        }
                        activeLatch.countDown();
                    }
                });
            }

            assertTrue(activeLatch.await(5, TimeUnit.SECONDS), "10 个 Agent 应成功激活");

            // 触发 n 次 agent/send
            var sendLatch = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int index = i;
                socket.emit("agent/send", JSONUtil.stringify(Map.of("agentId", agentIds.get(i), "event", Map.of("type", "test"))), new io.socket.client.Ack() {
                    @Override
                    public void call(Object... args) {
                        System.out.println("send done");
                        sendLatch.countDown();
                    }
                });
            }
            assertTrue(sendLatch.await(5, TimeUnit.SECONDS), n + " 次 agent/send 应成功发送");

            assertTrue(messageLatch.await(30, TimeUnit.SECONDS), "应收到 " + n + " 个 message 事件");
            for (int i = 0; i < n; i++) {
                assertNotNull(messages[i], "第 " + i + " 个 message 事件应包含数据");
                assertEquals(agentIds.get(i), messages[i].path("agentId").asText(), "第 " + i + " 个 message 事件应包含正确的 agentId");
            }
        });
    }

    static class TestWebsocketServer extends Websocket {
        @Override
        public Agent createAgent(ObjectNode params) {
            var agent = new Agent(UUID.randomUUID().toString(), "随便输出点啥，测试一下",  new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"), "https://dashscope.aliyuncs.com/compatible-mode/v1", 3), null);

            return agent;
        }

        public TestWebsocketServer (String ip, Integer port, IMessageBus messageBus, IMetaData metaData) {
            super(ip, port, messageBus, metaData);
        }



    }
}