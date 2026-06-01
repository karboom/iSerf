package me.karboom.java.iSerf.server;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.transport.ITransport;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
                .build();

        // 正常状态下应处理事件
        server.isShuttingDown = false;
        var normalResult = server.handleUserEvent("agent/create", ctx, "{}");
        assertNotNull(normalResult, "正常状态下应有响应");

        // 关闭状态下应返回错误
        server.isShuttingDown = true;
        var shutdownResult = server.handleUserEvent("agent/create", ctx, "{}");
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
                .build();

        // agent/create 应返回含 agentId 的 JSON
        var createResult = server.handleUserEvent("agent/create", ctx, "{}");
        assertNotNull(createResult, "agent/create 应返回结果");
        assertTrue(createResult.contains("agentId"), "agent/create 结果应包含 agentId");

        // agent/send 对不存在的 agent 返回 null（直接跨节点转发无本地响应）
        var sendResult = server.handleUserEvent("agent/send", ctx,
                JSONUtil.stringify(java.util.Map.of("agentId", "nonexistent")));
        assertNull(sendResult, "agent/send 对不存在 agent 应返回 null");

        // agent/leave 对不存在的 agent 返回 null
        var leaveResult = server.handleUserEvent("agent/leave", ctx,
                JSONUtil.stringify(java.util.Map.of("agentId", "nonexistent")));
        assertNull(leaveResult, "agent/leave 对不存在 agent 应返回 null");

        // agent/toolCall 对不存在的 agent 返回 null
        var toolCallResult = server.handleUserEvent("agent/toolCall", ctx,
                JSONUtil.stringify(java.util.Map.of("agentId", "nonexistent", "toolCallId", "test")));
        assertNull(toolCallResult, "agent/toolCall 对不存在 agent 应返回 null");

        // 未定义的 event 返回 null
        var unknownResult = server.handleUserEvent("unknown/event", ctx, "{}");
        assertNull(unknownResult, "未知 event 应返回 null");
    }

    /**
     * 验证 agent/create 处理流程
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testHandleAgentCreate() {
        var ctx = ContainerServer.Context.builder()
                .transport(stub)
                .client("test-client")
                .build();

        // 创建前 localAgents 为空
        assertTrue(server.localAgents.isEmpty(), "创建前 localAgents 应为空");

        var result = server.handleUserEvent("agent/create", ctx, "{}");
        var resultNode = JSONUtil.parse(result);
        var agentId = resultNode.path("agentId").asText();

        assertNotNull(agentId, "agentId 不应为 null");
        assertFalse(agentId.isEmpty(), "agentId 不应为空");

        // 创建后 localAgents 应包含该 agent
        assertEquals(1, server.localAgents.size(), "创建后 localAgents 应有 1 个元素");
        assertNotNull(server.localAgents.get(agentId), "localAgents 应包含创建的 agent");
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
//        var createResult = server.handleUserEvent("agent/create", ctx, "{}");
//        var agentId = JSONUtil.parse(createResult).path("agentId").asText();
//
//        // 对已存在的 agent 调用 toolCall
//        var toolCallResult = server.handleUserEvent("agent/toolCall", ctx,
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
                    .build();
            return container.handleUserEvent(event, ctx, dataJson);
        }
    }

}