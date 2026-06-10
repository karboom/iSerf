package me.karboom.java.iSerf.server.transport;

import io.socket.client.IO;
import io.socket.client.Socket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.server.TestContainer;
import me.karboom.java.iSerf.server.TestTeamLifecycle;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebsocketTransport 测试用例，验证 Socket.IO 服务生命周期、授权、事件分发和消息推送
 */
@Slf4j
class WebsocketTransportTest {

    private ContainerServer container;
    private TestWebsocketTransport transport;

    @BeforeEach
    void setUp() {
        container = new ContainerServer("127.0.0.1",
                new Pulsar("pulsar://localhost:6650"),
                new RedisSingle("redis://:RGluZ1NoZW5nMTIz@localhost:6379"),
                new TestContainer(),
                new TestTeamLifecycle()) {
            @Override
            public void eventInterceptor(Context ctx, String event, String dataJson) {}
        };
        transport = new TestWebsocketTransport(container, 9090);
        transport.start();
    }

    @AfterEach
    void tearDown() {
        transport.stop();
    }


    /**
     * 验证关闭状态下新连接被授权链拒绝
     */
    @SneakyThrows
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAuthorizationShutdown() {
        // 先标记关闭状态
        container.isShuttingDown = true;

        var socket = IO.socket("http://127.0.0.1:9090/user", new IO.Options() {{
            transports = new String[]{"websocket"};
            reconnection = false;
        }});

        var errorLatch = new CountDownLatch(1);
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> errorLatch.countDown());
        socket.connect();

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "关闭状态下客户端应被拒绝连接");
        socket.disconnect();
    }

    /**
     * 验证 agent.create 事件通过 Socket.IO 正确分发到 ContainerServer 并返回 agentId
     */
    @SneakyThrows
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testUserEventHandlerDispatch() {
        container.addTransport(transport);

        var options = new IO.Options();
        options.transports = new String[]{"websocket"};
        options.reconnection = false;
        var socket = IO.socket("http://127.0.0.1:9090/user", options);

        var connectLatch = new CountDownLatch(1);
        var createLatch = new CountDownLatch(1);
        var agentIdRef = new AtomicReferenceArray<String>(1);

        socket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        socket.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "客户端应能连接");

        socket.emit(ContainerServer.EVENT_AGENT_CREATE, "{}", new io.socket.client.Ack() {
            @Override
            public void call(Object... args) {
                if (args.length > 0 && args[0] instanceof String) {
                    var response = JSONUtil.parse((String) args[0]);
                    agentIdRef.set(0, response.path("agentId").asText());
                    createLatch.countDown();
                }
            }
        });

        assertTrue(createLatch.await(10, TimeUnit.SECONDS), "应收到 agent.create 的 ack 响应");
        var agentId = agentIdRef.get(0);
        assertNotNull(agentId, "agentId 不应为空");
        assertFalse(agentId.isEmpty(), "agentId 不应为空字符串");

        socket.disconnect();
    }

    /**
     * 验证 sendToClient 能将消息推送给已连接的客户端
     */
    @SneakyThrows
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testSendToClient() {
        var options = new IO.Options();
        options.transports = new String[]{"websocket"};
        options.reconnection = false;
        var socket = IO.socket("http://127.0.0.1:9090/user", options);

        var connectLatch = new CountDownLatch(1);
        var messageLatch = new CountDownLatch(1);
        var receivedRef = new AtomicReferenceArray<String>(1);

        socket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        socket.on("test-event", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                receivedRef.set(0, (String) args[0]);
                messageLatch.countDown();
            }
        });

        socket.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "客户端应能连接");

        // 从服务端获取已连接的 SocketIOClient，通过 sendToClient 推送消息
        var clients = transport.getServer().getNamespace("/user").getAllClients();
        assertFalse(clients.isEmpty(), "应有已连接的客户端");

        var serverClient = clients.iterator().next();
        transport.sendToClient(serverClient, "test-event", "{\"msg\":\"hello\"}");

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "客户端应收到推送消息");
        assertEquals("{\"msg\":\"hello\"}", receivedRef.get(0), "收到的消息内容应一致");

        socket.disconnect();
    }

    /**
     * 验证关闭状态下 EventInterceptor 拒绝事件并返回错误
     */
    @SneakyThrows
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testEventInterceptorShutdown() {
        var options = new IO.Options();
        options.transports = new String[]{"websocket"};
        options.reconnection = false;
        var socket = IO.socket("http://127.0.0.1:9090/user", options);

        var connectLatch = new CountDownLatch(1);
        socket.on(Socket.EVENT_CONNECT, args -> connectLatch.countDown());
        socket.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "客户端应能连接");

        // 设置关闭状态
        container.isShuttingDown = true;

        var ackLatch = new CountDownLatch(1);
        var ackRef = new AtomicReferenceArray<String>(1);

        socket.emit(ContainerServer.EVENT_AGENT_CREATE, "{}", new io.socket.client.Ack() {
            @Override
            public void call(Object... args) {
                if (args.length > 0 && args[0] instanceof String) {
                    ackRef.set(0, (String) args[0]);
                }
                ackLatch.countDown();
            }
        });

        assertTrue(ackLatch.await(10, TimeUnit.SECONDS), "应收到 ack 响应");
        var ackData = ackRef.get(0);
        assertNotNull(ackData, "ack 数据不应为空");
        assertTrue(ackData.contains("shutting down"), "关闭状态下应返回 shutting down 错误");

        socket.disconnect();
    }

    /**
     * 验证 sendToClient 传入非 SocketIOClient 类型时不抛异常
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSendToClientUnsupportedType() {
        // 传入非 SocketIOClient 类型不应抛异常
        assertDoesNotThrow(() -> transport.sendToClient("unsupported-handle", "event", "{}"),
                "sendToClient 传入不支持的类型不应抛出异常");
    }

    // ======================== 内部类 ========================

    /**
     * WebsocketTransport 的具体实现，用于测试
     */
    static class TestWebsocketTransport extends WebsocketTransport {

        public TestWebsocketTransport(ContainerServer container, Integer port) {
            super(container, port);
        }

        @Override
        public Object getUser(Object clientHandle) {
            return null;
        }
    }
}