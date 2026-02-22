package me.karboom.java.iSerf.server;

import io.socket.client.IO;
import io.socket.client.Socket;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;


class WebsocketTest {

    private static TestWebsocketServer server;
    private static TestWebsocketServer server2;

    @BeforeAll
    static void startCluster() {
        server = new TestWebsocketServer("127.0.0.1", 9092);
        server.start();

        server2 = new TestWebsocketServer("127.0.0.1", 9093);
        server2.start();
    }

    @AfterAll
    static void stopCluster() {
        server.stop();
        server2.stop();
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
        public TestWebsocketServer (String ip, Integer port) {
            super(ip, port);
        }

        @Override
        public List<String> getNodes() {
            return List.of("127.0.0.1:9092", "127.0.0.1:9093");
        }

        public void stop() {
            if (server != null) {
                server.stop();
            }
            if (scheduler != null) {
                scheduler.shutdown();
            }
        }
    }
}