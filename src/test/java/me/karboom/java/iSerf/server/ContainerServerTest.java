package me.karboom.java.iSerf.server;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.transport.ITransport;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContainerServer 测试用例，验证 transport 管理、生命周期、事件路由和 agent 操作
 */
@Slf4j
class ContainerServerTest {

    private TestContainer server;
    private StubTransport stub;

    @BeforeEach
    void setUp() {
        server = new TestContainer();
        stub = new StubTransport(server);
    }

    /**
     * 验证 server 启停生命周期
     */
    @SneakyThrows
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testStartStop() {
        server.addTransport(stub);

        // 启动前状态
        assertFalse(server.isShuttingDown, "启动前 isShuttingDown 应为 false");
        assertFalse(stub.started, "启动前 StubTransport 不应为已启动状态");

        server.start();

        // 等待启动完成
        Thread.sleep(1000);

        // 启动后状态
        assertTrue(stub.started, "start 后 StubTransport 应为已启动状态");
        // metaData 中应包含本节点
        var nodesAfterStart = server.metaData.getNodes();
        var nodeExists = nodesAfterStart.stream().anyMatch(n -> server.id.equals(n.id));
        assertTrue(nodeExists, "start 后 metaData 应包含本节点");

        server.stop();

        // 停止后状态
        assertTrue(server.isShuttingDown, "stop 后 isShuttingDown 应为 true");
        assertTrue(stub.stopped, "stop 后 StubTransport 应为已停止状态");
        // metaData 中不应再包含本节点
        var nodesAfterStop = server.metaData.getNodes();
        var nodeExistsAfterStop = nodesAfterStop.stream().anyMatch(n -> server.id.equals(n.id));
        assertFalse(nodeExistsAfterStop, "stop 后 metaData 不应包含本节点");
    }

    /**
     * 验证关闭状态下 handleUserEvent 返回错误
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testShuttingDown() {
        var ctx = ContainerServer.Context.builder()
                .transport(stub)
                .client("test-client")
                .serverId(server.id)
                .build();

        // 正常状态下应处理事件
        server.isShuttingDown = false;
        var normalResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, "{}");
        assertNotNull(normalResult, "正常状态下应有响应");

        // 关闭状态下应返回错误
        server.isShuttingDown = true;
        var shutdownResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, "{}");
        assertNotNull(shutdownResult, "关闭状态下应有响应");
        assertTrue(shutdownResult.contains("shutting down"), "关闭状态下应返回 shutting down 错误");
    }

    /**
     * 验证事件路由：不同 event 名称正确分发到对应 handler
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testHandleUserEventRouting() {
        var ctx = ContainerServer.Context.builder()
                .transport(stub)
                .client("test-client")
                .serverId(server.id)
                .build();

        // agent.create 应返回含 agentId 的 JSON，并派发给 transport
        var createResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, "{}");
        assertNotNull(createResult, "agent.create 应返回结果");
        assertTrue(createResult.contains("agentId"), "agent.create 结果应包含 agentId");
        assertEquals(1, stub.receivedMessages.size(), "本节点 agent.create 有返回值，应派发 1 条消息给 transport");

        // agent.send 对不存在的 agent 返回 null（直接跨节点转发无本地响应），无派发
        var sendEvent = java.util.Map.of(
                "agentId", "nonexistent",
                "event", java.util.Map.of(
                        "type", "MESSAGE",
                        "priority", 1,
                        "message", java.util.Map.of(
                                "type", "TEXT",
                                "text", "test",
                                "files", List.of("1", 2)
                        )
                )
        );
        var sendResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_SEND, JSONUtil.stringify(sendEvent));
        assertNull(sendResult, "agent.send 对不存在 agent 应返回 null");
        assertEquals(1, stub.receivedMessages.size(), "agent.send 无返回值，不应追加派发");

        // agent.unsubscribe 对不存在的 agent 返回 null，无派发
        var unsubscribeResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_UNSUBSCRIBE,
                JSONUtil.stringify(java.util.Map.of("agentId", "nonexistent")));
        assertNull(unsubscribeResult, "agent.unsubscribe 对不存在 agent 应返回 null");
        assertEquals(1, stub.receivedMessages.size(), "agent.unsubscribe 无返回值，不应追加派发");

        // agent.toolCall 对不存在的 agent 返回 null，无派发
        var toolCallResult = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_TOOL_CALL,
                JSONUtil.stringify(java.util.Map.of("agentId", "nonexistent", "toolCallId", "test")));
        assertNull(toolCallResult, "agent.toolCall 对不存在 agent 应返回 null");
        assertEquals(1, stub.receivedMessages.size(), "agent.toolCall 无返回值，不应追加派发");

        // 未定义的 event 返回 null，无派发
        var unknownResult = server.handleUserEvent(ctx, "unknown/event", "{}");
        assertNull(unknownResult, "未知 event 应返回 null");
        assertEquals(1, stub.receivedMessages.size(), "未知 event 无返回值，不应追加派发");
    }

    /**
     * 验证 agent.create 处理流程
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testHandleAgentCreate() {
        var ctx = ContainerServer.Context.builder()
                .transport(stub)
                .client("test-client")
                .serverId(server.id)
                .build();

        // 创建前 localAgents 为空
        assertTrue(server.localAgents.isEmpty(), "创建前 localAgents 应为空");

        var result = server.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, "{}");
        var resultNode = JSONUtil.parse(result);
        var agentId = resultNode.path("agentId").asText();

        assertNotNull(agentId, "agentId 不应为 null");
        assertFalse(agentId.isEmpty(), "agentId 不应为空");

        // 创建后 localAgents 应包含该 agent
        assertEquals(1, server.localAgents.size(), "创建后 localAgents 应有 1 个元素");
        assertNotNull(server.localAgents.get(agentId), "localAgents 应包含创建的 agent");

        // 本节点有返回值时应通过 transport 派发同名事件
        assertEquals(1, stub.receivedMessages.size(), "本节点 agent.create 有返回值，应派发 1 条消息");
        assertEquals(result, stub.receivedMessages.peek(), "派发的消息内容应与返回值一致");
    }

    /**
     * 验证本地 agent toolCall 处理流程
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testHandleToolCall() {
//        var ctx = ContainerServer.Context.builder()
//                .transport(stub)
//                .client("test-client")
//                .build();
//
//        // 先创建 agent
//        var createResult = server.handleUserEvent(ctx, "agent.create", "{}");
//        var agentId = JSONUtil.parse(createResult).path("agentId").asText();
//
//        // 对已存在的 agent 调用 toolCall
//        var toolCallResult = server.handleUserEvent(ctx, "agent.toolCall",
//                JSONUtil.stringify(java.util.Map.of("agentId", agentId, "toolCallId", "test-tool-call")));
//        // toolCall 应返回非空结果
//        assertNotNull(toolCallResult, "本地 agent toolCall 应返回结果");
    }

    // ======================== 内部类 ========================

    /**
     * 桩传输实现，在内存中模拟 ITransport，记录启停状态并支持 sendToClient 消息收集
     */
    @Slf4j
    static class StubTransport implements ITransport {

        public String transportId;
        public ContainerServer container;
        public ConcurrentLinkedQueue<String> receivedMessages = new ConcurrentLinkedQueue<>();
        public boolean started = false;
        public boolean stopped = false;

        public StubTransport(ContainerServer container) {
            this.container = container;
            this.transportId = DataUtil.getFlakeId();
        }

        @Override
        public void start() {
            log.debug("start StubTransport started: %s".formatted(transportId));
            started = true;
        }

        @Override
        public void stop() {
            log.debug("stop StubTransport stopped: %s".formatted(transportId));
            stopped = true;
        }

        @Override
        public String getTransportId() {
            return transportId;
        }

        @Override
        public void sendToClient(Object clientHandle, String event, String bodyJson) {
            log.debug("sendToClient event: %s, body: %s".formatted(event, bodyJson));
            receivedMessages.add(bodyJson);
        }

        /**
         * 模拟客户端事件，构造 Context 并委托给 ContainerServer 处理
         *
         * @param event    事件名
         * @param dataJson 请求数据 JSON 字符串
         * @return 响应 JSON 字符串，可为 null
         */
        public String simulateEvent(String event, String dataJson) {
            var ctx = ContainerServer.Context.builder()
                    .transport(this)
                    .client("stub-client-" + transportId)
                    .serverId(container.id)
                    .build();
            return container.handleUserEvent(ctx, event, dataJson);
        }
    }

}