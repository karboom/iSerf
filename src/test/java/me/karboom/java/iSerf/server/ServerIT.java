package me.karboom.java.iSerf.server;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentEvent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.persistence.AgentSnapshot;
import me.karboom.java.iSerf.agent.persistence.IPersistence;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.llm.text.BatchTaskInfo;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.server.lifecycle.IAgentLifecycle;
import me.karboom.java.iSerf.server.lifecycle.ITeamLifecycle;
import me.karboom.java.iSerf.server.messageBus.MemoryMessageBus;
import me.karboom.java.iSerf.server.metaData.MemoryMetaData;
import me.karboom.java.iSerf.server.transport.NoneTransport;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Server 集成测试：覆盖从创建 Server、启动、客户端发送各类事件、到关闭服务的完整流程
 */
@Slf4j
class ServerIT {

    private Server server;
    private MemoryMessageBus messageBus;
    private MemoryMetaData metaData;
    private NoneTransport transport;
    private final Map<String, Agent> allAgents = new ConcurrentHashMap<>();
    private TestPersistence testPersistence;

    /** 可追踪调用的 Persistence 实现，用于验证 deactivate/activate 时的持久化操作 */
    static class TestPersistence implements IPersistence {
        public int syncMemoryCount = 0;
        public int syncEventCount = 0;
        public int syncToolCallCount = 0;
        public int syncPlanCount = 0;
        public int loadCount = 0;
        public int createCount = 0;
        public final Map<String, Agent> storedAgents = new ConcurrentHashMap<>();

        @Override
        public AgentSnapshot load(AgentMetadata metadata) {
            loadCount++;
            return AgentSnapshot.builder()
                    .memories(List.of())
                    .events(List.of())
                    .toolCalls(List.of())
                    .plans(List.of())
                    .build();
        }

        @Override
        public void remove(Agent agent) {}

        @Override
        public void syncMemory(Agent agent) {
            syncMemoryCount++;
            storedAgents.put(agent.metadata.getId(), agent);
        }

        @Override
        public void addMemory(Agent agent) {}

        @Override
        public void syncEvent(Agent agent) {
            syncEventCount++;
        }

        @Override
        public void syncToolCall(Agent agent) {
            syncToolCallCount++;
        }

        @Override
        public void addToolCall(Agent agent) {}

        @Override
        public void syncPlan(Agent agent) {
            syncPlanCount++;
        }

        @Override
        public void syncMetadata(Agent agent) {}

        @Override
        public List<AgentSnapshot> search(Map<String, Object> params) { return List.of(); }

        @Override
        public Boolean create(Agent agent) {
            createCount++;
            return true;
        }

        public void reset() {
            syncMemoryCount = 0;
            syncEventCount = 0;
            syncToolCallCount = 0;
            syncPlanCount = 0;
            loadCount = 0;
            createCount = 0;
            storedAgents.clear();
        }
    }

    /** 轻量 Agent 工厂：FakeLlmProvider 返回 Flux.empty()，不依赖真实 LLM */
    static Agent makeAgent(IPersistence persistence) {
        var provider = new ILlmProvider() {
            @Override
            public IText get(List<Tool<?>> tools, Class<?> outputFormat, Integer retryTimes) {
                return new IText() {
                    @Override
                    public Flux<Output> send(List<AgentMessage> memory, Class<?> of, List<Tool<?>> t) { return Flux.empty(); }
                    @Override
                    public Output query(List<AgentMessage> messages, Class<?> of) { return null; }
                    @Override
                    public String batch(List<List<AgentMessage>> mb, Class<?> of) { return null; }
                    @Override
                    public BatchTaskInfo taskStatus(String taskId) { return null; }
                    @Override
                    public List<Output> taskResult(BatchTaskInfo task) { return List.of(); }
                };
            }
        };
        var config = new AgentConfig();
        var metadata = new AgentMetadata();
        metadata.setId(UUID.randomUUID().toString());
        config.setMetadata(metadata);
        config.setPrompt("test");
        config.setLlm(provider);
        config.setPersistence(persistence);
        return new Agent(config);
    }

    @BeforeEach
    void setUp() {
        messageBus = new MemoryMessageBus();
        metaData = new MemoryMetaData();
        transport = new NoneTransport();
        testPersistence = new TestPersistence();

        server = new Server("127.0.0.1", messageBus, metaData,
                new IAgentLifecycle() {
                    @Override
                    public Agent createAgent(Server.Context ctx, ObjectNode params) {
                        var agent = makeAgent(testPersistence);
                        allAgents.put(agent.metadata.getId(), agent);
                        return agent;
                    }
                    @Override
                    public List<Agent> listAgent(Server.Context ctx, ObjectNode params) {
                        var keyword = params.path("keyword").asText();
                        return server.localAgents.values().stream()
                                .filter(a -> keyword.isEmpty() || a.metadata.getId().contains(keyword))
                                .toList();
                    }
                    @Override
                    public Agent removeAgent(Server.Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent editAgent(Server.Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent detailAgent(Server.Context ctx, ObjectNode params) {
                        var id = params.path("agentId").asText();
                        return allAgents.get(id);
                    }
                    @Override
                    public ObjectNode serializeAgent(Agent agent) {
                        return JSONUtil.create().put("id", agent.metadata.getId());
                    }
                },
                new ITeamLifecycle() {
                    @Override
                    public Team createTeam(Server.Context ctx, ObjectNode params) {
                        return new Team(makeAgent(testPersistence), List.of()) {};
                    }
                    @Override
                    public List<Team> listTeam(Server.Context ctx, ObjectNode params) {
                        return List.copyOf(server.localTeams.values());
                    }
                    @Override
                    public Team removeTeam(Server.Context ctx, ObjectNode params) {
                        var teamId = params.path("teamId").asText();
                        return server.localTeams.get(teamId);
                    }
                    @Override
                    public Team editTeam(Server.Context ctx, ObjectNode params) {
                        return new Team(makeAgent(testPersistence), List.of()) {};
                    }
                    @Override
                    public Team detailTeam(Server.Context ctx, ObjectNode params) {
                        var id = params.path("teamId").asText();
                        return server.localTeams.get(id);
                    }
                    @Override
                    public ObjectNode serializeTeam(Team team) {
                        return JSONUtil.create().put("id", "team-id");
                    }
                }) {
            @Override
            public void eventInterceptor(Context ctx, String event, String dataJson) {}

            @Override
            public Exception exceptionHandler(Context ctx, String event, String msgId, Exception e) {
                return e;
            }
        };
        server.addTransport(transport);
        server.persistence = testPersistence;
    }

    // region ======================== Helpers ========================

    private Server.Context buildContext() {
        return Server.Context.builder()
                .transport(transport)
                .client("test-client")
                .isInternal(false)
                .server(server)
                .build();
    }

    private String buildRequest(String msgId, ObjectNode body) {
        var msg = new Server.Message<ObjectNode>() {};
        msg.setMsgId(msgId);
        msg.setBody(body);
        return JSONUtil.stringify(msg);
    }

    private ObjectNode parseResponseBody(String responseJson) {
        var msgNode = JSONUtil.parse(responseJson);
        return (ObjectNode) msgNode.path("body").path("data");
    }

    // endregion

    // region ======================== 完整集成流程 ========================

    @Nested
    class FullLifecycle {

        /**
         * 完整集成流程：创建 Server → 启动 → Agent 事件 → Team 事件 → 关闭
         */
        @Test
        @Timeout(30)
        void testFullLifecycle() {
            // region 1. 启动服务
            server.start();
            assertTrue(metaData.getNodes().stream().anyMatch(n -> n.getId().equals(server.id)),
                    "启动后节点应注册到 metaData");
            assertFalse(server.isShuttingDown);
            // endregion

            var ctx = buildContext();

            // region 2. agent.create
            var createReq = buildRequest("msg-1", JSONUtil.create().put("name", "test-agent"));
            var createResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_CREATE, createReq);
            assertNotNull(createResp);
            var createData = parseResponseBody(createResp);
            var agentId = createData.path("agentId").asText();
            assertFalse(agentId.isEmpty(), "agentId 不应为空");
            assertTrue(server.localAgents.containsKey(agentId), "Agent 应在 localAgents 中");
            assertEquals(server.id, metaData.getAgentStay(agentId), "metaData 应记录 Agent 所在节点");
            log.debug("testFullLifecycle agent created: %s".formatted(agentId));
            // endregion

            // region 3. agent.list
            var listReq = buildRequest("msg-2", JSONUtil.create().put("keyword", ""));
            var listResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_LIST, listReq);
            assertNotNull(listResp);
            var listData = parseResponseBody(listResp);
            var agents = listData.path("agents");
            assertTrue(agents.isArray());
            assertEquals(1, agents.size(), "应列出 1 个 Agent");
            assertEquals(agentId, agents.get(0).path("id").asText());
            // endregion

            // region 4. agent.detail
            var detailReq = buildRequest("msg-3", JSONUtil.create().put("agentId", agentId));
            var detailResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_DETAIL, detailReq);
            assertNotNull(detailResp);
            var detailData = parseResponseBody(detailResp);
            assertEquals(agentId, detailData.path("id").asText());
            // endregion

            // region 5. agent.deactivate - 验证 persistence sync 调用
            var deactivateReq = buildRequest("msg-4", JSONUtil.create().put("agentId", agentId));
            var deactivateResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_DEACTIVATE, deactivateReq);
            assertNotNull(deactivateResp);
            var deactivateData = parseResponseBody(deactivateResp);
            assertEquals(agentId, deactivateData.path("agentId").asText());
            assertFalse(server.localAgents.containsKey(agentId), "deactivate 后 Agent 应从 localAgents 移除");
            assertNull(metaData.getAgentStay(agentId), "deactivate 后 metaData 应移除 Agent 路由");
            // 验证 persistence sync 方法被调用
            assertEquals(1, testPersistence.syncMemoryCount, "deactivate 应调用 syncMemory");
            assertEquals(1, testPersistence.syncEventCount, "deactivate 应调用 syncEvent");
            assertEquals(1, testPersistence.syncToolCallCount, "deactivate 应调用 syncToolCall");
            assertEquals(1, testPersistence.syncPlanCount, "deactivate 应调用 syncPlan");
            assertTrue(testPersistence.storedAgents.containsKey(agentId), "deactivate 应将 Agent 存储到 persistence");
            // endregion

            // region 6. agent.activate - 验证从 persistence 加载
            var activateReq = buildRequest("msg-5", JSONUtil.create().put("agentId", agentId));
            var activateResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_ACTIVATE, activateReq);
            assertNotNull(activateResp);
            var activateData = parseResponseBody(activateResp);
            assertEquals(agentId, activateData.path("agentId").asText());
            assertTrue(server.localAgents.containsKey(agentId), "activate 后 Agent 应回到 localAgents");
            assertEquals(server.id, metaData.getAgentStay(agentId), "activate 后 metaData 应重新记录路由");
            // 验证 Agent 是从 persistence 加载的（通过 allAgents 缓存验证）
            assertTrue(allAgents.containsKey(agentId), "activate 后 Agent 应在 allAgents 中");
            // endregion

            // region 7. agent.subscribe
            var subscribeReq = buildRequest("msg-6", JSONUtil.create().put("agentId", agentId));
            var subscribeResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_SUBSCRIBE, subscribeReq);
            assertNull(subscribeResp, "subscribe 无返回值");
            assertTrue(server.localSubscriptions.containsKey(agentId), "应注册本地订阅");
            // endregion

            // region 8. agent.send
            var userMsg = AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER).type(AgentMessage.TYPE.TEXT).text("hello").build();
            var eventNode = JSONUtil.convert(
                    AgentEvent.builder().type(AgentEvent.Type.MESSAGE).priority(1).message(userMsg).build());
            var sendReq = buildRequest("msg-7",
                    JSONUtil.create().put("agentId", agentId).set("event", eventNode));
            var sendResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_SEND, sendReq);
            assertNull(sendResp, "send 无返回值");
            // endregion

            // region 9. agent.unsubscribe
            var unsubscribeReq = buildRequest("msg-8", JSONUtil.create().put("agentId", agentId));
            var unsubscribeResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_UNSUBSCRIBE, unsubscribeReq);
            assertNull(unsubscribeResp, "unsubscribe 无返回值");
            assertFalse(server.localSubscriptions.containsKey(agentId), "订阅应已移除");
            // endregion

            // region 10. agent.deactivate - 再次验证 persistence sync 调用
            var deactivate2Req = buildRequest("msg-9b", JSONUtil.create().put("agentId", agentId));
            var deactivate2Resp = server.handleUserEvent(ctx, Server.EVENT_AGENT_DEACTIVATE, deactivate2Req);
            assertNotNull(deactivate2Resp);
            var deactivate2Data = parseResponseBody(deactivate2Resp);
            assertEquals(agentId, deactivate2Data.path("agentId").asText());
            assertFalse(server.localAgents.containsKey(agentId), "deactivate 后 Agent 应从 localAgents 移除");
            assertNull(metaData.getAgentStay(agentId), "deactivate 后 metaData 应移除 Agent 路由");
            // 验证 persistence sync 方法再次被调用（计数 +1）
            assertEquals(2, testPersistence.syncMemoryCount, "第二次 deactivate 应再次调用 syncMemory");
            assertEquals(2, testPersistence.syncEventCount, "第二次 deactivate 应再次调用 syncEvent");
            assertEquals(2, testPersistence.syncToolCallCount, "第二次 deactivate 应再次调用 syncToolCall");
            assertEquals(2, testPersistence.syncPlanCount, "第二次 deactivate 应再次调用 syncPlan");
            // endregion

            // region 10. team.create
            var teamCreateReq = buildRequest("msg-9", JSONUtil.create().put("name", "test-team"));
            var teamCreateResp = server.handleUserEvent(ctx, Server.EVENT_TEAM_CREATE, teamCreateReq);
            assertNotNull(teamCreateResp);
            var teamCreateData = parseResponseBody(teamCreateResp);
            var teamId = teamCreateData.path("teamId").asText();
            assertFalse(teamId.isEmpty(), "teamId 不应为空");
            assertTrue(server.localTeams.containsKey(teamId), "Team 应在 localTeams 中");
            assertEquals(server.id, metaData.getTeamStay(teamId), "metaData 应记录 Team 所在节点");
            // endregion

            // region 11. team.list
            var teamListReq = buildRequest("msg-10", JSONUtil.create().put("keyword", ""));
            var teamListResp = server.handleUserEvent(ctx, Server.EVENT_TEAM_LIST, teamListReq);
            assertNotNull(teamListResp);
            var teamListData = parseResponseBody(teamListResp);
            var teams = teamListData.path("teams");
            assertTrue(teams.isArray());
            assertEquals(1, teams.size(), "应列出 1 个 Team");
            // endregion

            // region 12. team.detail
            var teamDetailReq = buildRequest("msg-11", JSONUtil.create().put("teamId", teamId));
            var teamDetailResp = server.handleUserEvent(ctx, Server.EVENT_TEAM_DETAIL, teamDetailReq);
            assertNotNull(teamDetailResp);
            var teamDetailData = parseResponseBody(teamDetailResp);
            assertEquals("team-id", teamDetailData.path("id").asText());
            // endregion

            // region 13. team.edit
            var teamEditReq = buildRequest("msg-12", JSONUtil.create().put("teamId", teamId));
            var teamEditResp = server.handleUserEvent(ctx, Server.EVENT_TEAM_EDIT, teamEditReq);
            assertNotNull(teamEditResp);
            // endregion

            // region 14. team.remove
            var teamRemoveReq = buildRequest("msg-13", JSONUtil.create().put("teamId", teamId));
            var teamRemoveResp = server.handleUserEvent(ctx, Server.EVENT_TEAM_REMOVE, teamRemoveReq);
            assertNotNull(teamRemoveResp);
            var teamRemoveData = parseResponseBody(teamRemoveResp);
            assertEquals(teamId, teamRemoveData.path("teamId").asText());
            assertFalse(server.localTeams.containsKey(teamId), "remove 后 Team 应从 localTeams 移除");
            assertNull(metaData.getTeamStay(teamId), "remove 后 metaData 应移除 Team 路由");
            // endregion

            // region 15. 关闭服务
            server.stop();
            assertTrue(server.isShuttingDown, "关闭后 isShuttingDown 应为 true");
            assertTrue(metaData.getNodes().stream().noneMatch(n -> n.getId().equals(server.id)),
                    "关闭后节点应从 metaData 移除");
            // endregion
        }
    }

    // endregion

    // region ======================== 多客户端场景 ========================

    @Nested
    class MultiClient {

        /**
         * 多客户端场景：两个客户端分别创建 Agent 并独立操作
         */
        @Test
        @Timeout(15)
        void testMultiClientAgentLifecycle() {
            server.start();

            var client1Ctx = Server.Context.builder()
                    .transport(transport)
                    .client("client-1")
                    .isInternal(false)
                    .server(server)
                    .build();

            var client2Ctx = Server.Context.builder()
                    .transport(transport)
                    .client("client-2")
                    .isInternal(false)
                    .server(server)
                    .build();

            // region 客户端1 创建 Agent
            var create1Req = buildRequest("msg-c1-create", JSONUtil.create().put("name", "agent-1"));
            var create1Resp = server.handleUserEvent(client1Ctx, Server.EVENT_AGENT_CREATE, create1Req);
            var agentId1 = parseResponseBody(create1Resp).path("agentId").asText();
            assertFalse(agentId1.isEmpty());
            // endregion

            // region 客户端2 创建 Agent
            var create2Req = buildRequest("msg-c2-create", JSONUtil.create().put("name", "agent-2"));
            var create2Resp = server.handleUserEvent(client2Ctx, Server.EVENT_AGENT_CREATE, create2Req);
            var agentId2 = parseResponseBody(create2Resp).path("agentId").asText();
            assertFalse(agentId2.isEmpty());
            assertNotEquals(agentId1, agentId2, "两个客户端创建的 Agent ID 应不同");
            // endregion

            // region 客户端1 列出 Agent，应能看到两个
            var listReq = buildRequest("msg-c1-list", JSONUtil.create().put("keyword", ""));
            var listResp = server.handleUserEvent(client1Ctx, Server.EVENT_AGENT_LIST, listReq);
            var listData = parseResponseBody(listResp);
            assertEquals(2, listData.path("agents").size(), "应列出 2 个 Agent");
            // endregion

            // region 客户端2 查看 Agent1 详情
            var detailReq = buildRequest("msg-c2-detail", JSONUtil.create().put("agentId", agentId1));
            var detailResp = server.handleUserEvent(client2Ctx, Server.EVENT_AGENT_DETAIL, detailReq);
            assertNotNull(detailResp);
            assertEquals(agentId1, parseResponseBody(detailResp).path("id").asText());
            // endregion

            // region 客户端1 订阅 Agent1，客户端2 发送消息
            var subscribeReq = buildRequest("msg-c1-sub", JSONUtil.create().put("agentId", agentId1));
            server.handleUserEvent(client1Ctx, Server.EVENT_AGENT_SUBSCRIBE, subscribeReq);
            assertTrue(server.localSubscriptions.containsKey(agentId1));

            var userMsg = AgentMessage.builder()
                    .role(AgentMessage.ROLE.USER).type(AgentMessage.TYPE.TEXT).text("from client2").build();
            var eventNode = JSONUtil.convert(
                    AgentEvent.builder().type(AgentEvent.Type.MESSAGE).priority(1).message(userMsg).build());
            var sendReq = buildRequest("msg-c2-send",
                    JSONUtil.create().put("agentId", agentId1).set("event", eventNode));
            server.handleUserEvent(client2Ctx, Server.EVENT_AGENT_SEND, sendReq);

            // 客户端1 取消订阅
            var unsubscribeReq = buildRequest("msg-c1-unsub", JSONUtil.create().put("agentId", agentId1));
            server.handleUserEvent(client1Ctx, Server.EVENT_AGENT_UNSUBSCRIBE, unsubscribeReq);
            assertFalse(server.localSubscriptions.containsKey(agentId1));
            // endregion

            server.stop();
            assertTrue(server.isShuttingDown);
        }
    }

    // endregion

    // region ======================== 关闭中拒绝请求 ========================

    @Nested
    class ShutdownReject {

        /**
         * 关闭中的 Server 应拒绝新请求并返回错误
         */
        @Test
        @Timeout(10)
        void testShutdownRejectsEvents() {
            server.start();

            var ctx = buildContext();

            // 先正常创建一个 Agent
            var createReq = buildRequest("msg-pre", JSONUtil.create());
            var createResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_CREATE, createReq);
            assertNotNull(createResp);
            assertTrue(createResp.contains("agentId"));

            // 关闭服务
            server.stop();
            assertTrue(server.isShuttingDown);

            // 关闭后发送事件应返回错误
            var postShutdownReq = buildRequest("msg-post", JSONUtil.create());
            var postShutdownResp = server.handleUserEvent(ctx, Server.EVENT_AGENT_CREATE, postShutdownReq);
            assertNotNull(postShutdownResp);
            assertTrue(postShutdownResp.contains("shutting down"),
                    "关闭后应返回 shutting down 错误");
        }
    }

    // endregion
}
