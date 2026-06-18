package me.karboom.java.iSerf.server;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentConfig;
import me.karboom.java.iSerf.agent.AgentEvent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.agent.AgentMetadata;
import me.karboom.java.iSerf.agent.persistence.NfsPersistence;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.llm.text.BatchTaskInfo;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.server.BO.Context;
import me.karboom.java.iSerf.server.BO.Message;
import me.karboom.java.iSerf.server.lifecycle.IAgentLifecycle;
import me.karboom.java.iSerf.server.lifecycle.ITeamLifecycle;
import me.karboom.java.iSerf.server.messageBus.MemoryMessageBus;
import me.karboom.java.iSerf.server.metaData.MemoryMetaData;
import me.karboom.java.iSerf.server.transport.NoneTransport;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Server 集成测试：覆盖三节点集群场景，使用真实 NfsPersistence
 */
@Slf4j
class ServerIT {

    private Server server1;
    private Server server2;
    private Server server3;
    private MemoryMessageBus messageBus;
    private MemoryMetaData metaData;
    private String persistenceBaseDir;
    private final Map<String, Agent> allAgents = new ConcurrentHashMap<>();

    /** 轻量 Agent 工厂：FakeLlmProvider 返回 Flux.empty()，不依赖真实 LLM */
    static Agent makeAgent(String persistenceBaseDir) {
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
        metadata.setOrgId("test-org");
        metadata.setUserId("test-user");
        config.setMetadata(metadata);
        config.setPrompt("test");
        config.setLlm(provider);
        config.setPersistence(new NfsPersistence(persistenceBaseDir));
        return new Agent(config);
    }

    /** 创建 Server 实例 */
    private Server createServer(String clusterIp, MemoryMessageBus bus, MemoryMetaData metaData, String persistenceBaseDir) {
        return new Server(clusterIp, bus, metaData,
                new IAgentLifecycle() {
                    @Override
                    public Agent createAgent(Context ctx, ObjectNode params) {
                        var agent = makeAgent(persistenceBaseDir);
                        allAgents.put(agent.metadata.getId(), agent);
                        return agent;
                    }
                    @Override
                    public List<Agent> listAgent(Context ctx, ObjectNode params) {
                        var keyword = params.path("keyword").asText();
                        return ctx.server.localAgents.values().stream()
                                .filter(a -> keyword.isEmpty() || a.metadata.getId().contains(keyword))
                                .toList();
                    }
                    @Override
                    public Agent removeAgent(Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent editAgent(Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent detailAgent(Context ctx, ObjectNode params) {
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
                    public Team createTeam(Context ctx, ObjectNode params) {
                        return new Team(makeAgent(persistenceBaseDir), List.of()) {};
                    }
                    @Override
                    public List<Team> listTeam(Context ctx, ObjectNode params) {
                        return List.copyOf(ctx.server.localTeams.values());
                    }
                    @Override
                    public Team removeTeam(Context ctx, ObjectNode params) {
                        var teamId = params.path("teamId").asText();
                        return ctx.server.localTeams.get(teamId);
                    }
                    @Override
                    public Team editTeam(Context ctx, ObjectNode params) {
                        return new Team(makeAgent(persistenceBaseDir), List.of()) {};
                    }
                    @Override
                    public Team detailTeam(Context ctx, ObjectNode params) {
                        var id = params.path("teamId").asText();
                        return ctx.server.localTeams.get(id);
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
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        messageBus = new MemoryMessageBus();
        metaData = new MemoryMetaData();
        persistenceBaseDir = tempDir.resolve("agents").toString();

        server1 = createServer("127.0.0.1", messageBus, metaData, persistenceBaseDir);
        server2 = createServer("127.0.0.2", messageBus, metaData, persistenceBaseDir);
        server3 = createServer("127.0.0.3", messageBus, metaData, persistenceBaseDir);

        // 设置 server 的 persistence 使用临时目录
        var nfsPersistence = new NfsPersistence(persistenceBaseDir);
        server1.persistence = nfsPersistence;
        server2.persistence = nfsPersistence;
        server3.persistence = nfsPersistence;

        server1.addTransport(new NoneTransport());
        server2.addTransport(new NoneTransport());
        server3.addTransport(new NoneTransport());
    }

    @AfterEach
    void tearDown() {
        if (server1 != null && !server1.isShuttingDown) server1.stop();
        if (server2 != null && !server2.isShuttingDown) server2.stop();
        if (server3 != null && !server3.isShuttingDown) server3.stop();
    }

    // region ======================== Helpers ========================

    private Context buildContext(Server server) {
        return Context.builder()
                .transport(server.transports.getFirst())
                .client("test-client")
                .isInternal(false)
                .server(server)
                .build();
    }

    private String buildRequest(String msgId, ObjectNode body) {
        var msg = new Message<ObjectNode>() {};
        msg.setMsgId(msgId);
        msg.setBody(body);
        return JSONUtil.stringify(msg);
    }

    private ObjectNode parseResponseBody(String responseJson) {
        var msgNode = JSONUtil.parse(responseJson);
        return (ObjectNode) msgNode.path("body").path("data");
    }

    // endregion

    // region ======================== 节点注册 ========================

    @Nested
    class NodeRegistration {

        /**
         * 三节点启动后均注册到 metaData
         */
        @Test
        @Timeout(10)
        void testAllNodesRegistered() {
            server1.start();
            server2.start();
            server3.start();

            var nodes = metaData.getNodes();
            assertEquals(3, nodes.size(), "应有 3 个节点注册");

            var nodeIds = nodes.stream().map(n -> n.getId()).toList();
            assertTrue(nodeIds.contains(server1.id), "server1 应注册");
            assertTrue(nodeIds.contains(server2.id), "server2 应注册");
            assertTrue(nodeIds.contains(server3.id), "server3 应注册");

            server1.stop();
            server2.stop();
            server3.stop();

            assertTrue(metaData.getNodes().isEmpty(), "停止后应无节点");
        }
    }

    // endregion

    // region ======================== 多节点 Agent 创建 ========================

    @Nested
    class MultiNodeAgentCreate {

        /**
         * 在不同节点创建 Agent，验证 localAgents 和 metaData 路由
         */
        @Test
        @Timeout(15)
        void testAgentCreateOnDifferentNodes() {
            server1.start();
            server2.start();
            server3.start();

            var ctx1 = buildContext(server1);
            var ctx2 = buildContext(server2);
            var ctx3 = buildContext(server3);

            // 在 server1 创建 Agent
            var create1Req = buildRequest("msg-1", JSONUtil.create().put("name", "agent-on-node1"));
            var create1Resp = server1.handleUserEvent(ctx1, Server.EVENT_AGENT_CREATE, create1Req);
            assertNotNull(create1Resp);
            var agentId1 = parseResponseBody(create1Resp).path("agentId").asText();
            assertFalse(agentId1.isEmpty(), "agentId1 不应为空");
            assertTrue(server1.localAgents.containsKey(agentId1), "Agent1 应在 server1.localAgents");
            assertEquals(server1.id, metaData.getAgentStay(agentId1), "metaData 应记录 Agent1 在 server1");

            // 在 server2 创建 Agent
            var create2Req = buildRequest("msg-2", JSONUtil.create().put("name", "agent-on-node2"));
            var create2Resp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_CREATE, create2Req);
            assertNotNull(create2Resp);
            var agentId2 = parseResponseBody(create2Resp).path("agentId").asText();
            assertFalse(agentId2.isEmpty(), "agentId2 不应为空");
            assertTrue(server2.localAgents.containsKey(agentId2), "Agent2 应在 server2.localAgents");
            assertEquals(server2.id, metaData.getAgentStay(agentId2), "metaData 应记录 Agent2 在 server2");

            // 在 server3 创建 Agent
            var create3Req = buildRequest("msg-3", JSONUtil.create().put("name", "agent-on-node3"));
            var create3Resp = server3.handleUserEvent(ctx3, Server.EVENT_AGENT_CREATE, create3Req);
            assertNotNull(create3Resp);
            var agentId3 = parseResponseBody(create3Resp).path("agentId").asText();
            assertFalse(agentId3.isEmpty(), "agentId3 不应为空");
            assertTrue(server3.localAgents.containsKey(agentId3), "Agent3 应在 server3.localAgents");
            assertEquals(server3.id, metaData.getAgentStay(agentId3), "metaData 应记录 Agent3 在 server3");

            // 验证各节点 localAgents 数量
            assertEquals(1, server1.localAgents.size(), "server1 应有 1 个 Agent");
            assertEquals(1, server2.localAgents.size(), "server2 应有 1 个 Agent");
            assertEquals(1, server3.localAgents.size(), "server3 应有 1 个 Agent");
        }
    }

    // endregion

    // region ======================== 跨节点 Agent 操作 ========================

    @Nested
    class CrossNodeAgentOperations {

        /**
         * 从 server1 远程 deactivate server2 上的 Agent
         */
        @Test
        @Timeout(15)
        void testRemoteDeactivate() {
            server1.start();
            server2.start();
            server3.start();

            var ctx1 = buildContext(server1);
            var ctx2 = buildContext(server2);

            // 在 server2 创建 Agent
            var createReq = buildRequest("msg-create", JSONUtil.create().put("name", "remote-agent"));
            var createResp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_CREATE, createReq);
            var agentId = parseResponseBody(createResp).path("agentId").asText();
            assertTrue(server2.localAgents.containsKey(agentId), "Agent 应在 server2.localAgents");
            assertEquals(server2.id, metaData.getAgentStay(agentId), "metaData 应记录 Agent 在 server2");

            // 从 server1 远程 deactivate
            var deactivateReq = buildRequest("msg-deactivate", JSONUtil.create().put("agentId", agentId));
            var deactivateResp = server1.handleUserEvent(ctx1, Server.EVENT_AGENT_DEACTIVATE, deactivateReq);
            // 远程操作返回 null（异步）
            assertNull(deactivateResp, "远程 deactivate 应返回 null");

            // 等待消息总线处理
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            // 验证 Agent 已从 server2 移除
            assertFalse(server2.localAgents.containsKey(agentId), "deactivate 后 Agent 应从 server2.localAgents 移除");
            assertNull(metaData.getAgentStay(agentId), "deactivate 后 metaData 应移除路由");
        }

        /**
         * 在 server2 上 activate Agent（从 NfsPersistence 恢复）
         */
        @Test
        @Timeout(15)
        void testRemoteActivate() {
            server1.start();
            server2.start();
            server3.start();

            var ctx2 = buildContext(server2);

            // 在 server2 创建 Agent
            var createReq = buildRequest("msg-create", JSONUtil.create().put("name", "remote-agent"));
            var createResp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_CREATE, createReq);
            var agentId = parseResponseBody(createResp).path("agentId").asText();

            // 先 deactivate
            var deactivateReq = buildRequest("msg-deactivate", JSONUtil.create().put("agentId", agentId));
            server2.handleUserEvent(ctx2, Server.EVENT_AGENT_DEACTIVATE, deactivateReq);
            assertFalse(server2.localAgents.containsKey(agentId), "deactivate 后 Agent 应不在 server2.localAgents");

            // 在 server2 上 activate（从 NfsPersistence 恢复）
            var activateReq = buildRequest("msg-activate", JSONUtil.create().put("agentId", agentId));
            var activateResp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_ACTIVATE, activateReq);
            assertNotNull(activateResp, "activate 应有响应");
            var activatedAgentId = parseResponseBody(activateResp).path("agentId").asText();
            assertEquals(agentId, activatedAgentId, "activate 返回的 agentId 应一致");

            // 验证 Agent 恢复到 server2
            assertTrue(server2.localAgents.containsKey(agentId), "activate 后 Agent 应回到 server2.localAgents");
            assertEquals(server2.id, metaData.getAgentStay(agentId), "activate 后 metaData 应重新记录路由");
        }
    }

    // endregion

    // region ======================== Team 生命周期 ========================

    @Nested
    class TeamLifecycle {

        /**
         * Team 创建、列表、详情、删除
         */
        @Test
        @Timeout(15)
        void testTeamLifecycle() {
            server1.start();
            server2.start();
            server3.start();

            var ctx1 = buildContext(server1);
            var ctx2 = buildContext(server2);

            // 在 server1 创建 Team
            var createReq = buildRequest("msg-team-create", JSONUtil.create().put("name", "test-team"));
            var createResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_CREATE, createReq);
            assertNotNull(createResp);
            var teamId = parseResponseBody(createResp).path("teamId").asText();
            assertFalse(teamId.isEmpty(), "teamId 不应为空");
            assertTrue(server1.localTeams.containsKey(teamId), "Team 应在 server1.localTeams");
            assertEquals(server1.id, metaData.getTeamStay(teamId), "metaData 应记录 Team 在 server1");

            // 在 server1 列出 Team
            var listReq = buildRequest("msg-team-list", JSONUtil.create().put("keyword", ""));
            var listResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_LIST, listReq);
            assertNotNull(listResp);
            var teams = parseResponseBody(listResp).path("teams");
            assertTrue(teams.isArray());
            assertEquals(1, teams.size(), "应列出 1 个 Team");

            // 在 server2 列出 Team（应通过共享 metaData 可见）
            var listResp2 = server2.handleUserEvent(ctx2, Server.EVENT_TEAM_LIST, listReq);
            assertNotNull(listResp2);
            var teams2 = parseResponseBody(listResp2).path("teams");
            // 注意：listTeam 通过 lifecycle 实现，这里返回的是 ctx.server.localTeams
            // 所以 server2 看不到 server1 的 team，除非通过共享存储
            assertEquals(0, teams2.size(), "server2.localTeams 应为空（Team 在 server1）");

            // 在 server1 删除 Team
            var removeReq = buildRequest("msg-team-remove", JSONUtil.create().put("teamId", teamId));
            var removeResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_REMOVE, removeReq);
            assertNotNull(removeResp);
            var removedTeamId = parseResponseBody(removeResp).path("teamId").asText();
            assertEquals(teamId, removedTeamId, "删除的 teamId 应一致");
            assertFalse(server1.localTeams.containsKey(teamId), "remove 后 Team 应从 server1.localTeams 移除");
            assertNull(metaData.getTeamStay(teamId), "remove 后 metaData 应移除 Team 路由");
        }
    }

    // endregion

    // region ======================== 完整集成流程 ========================

    @Nested
    class FullLifecycle {

        /**
         * 完整集成流程：三节点启动 → Agent 创建/ deactivate/activate → Team 操作 → 关闭
         */
        @Test
        @Timeout(30)
        void testFullLifecycle() {
            // region 1. 启动三节点
            server1.start();
            server2.start();
            server3.start();
            assertEquals(3, metaData.getNodes().size(), "应有 3 个节点");
            // endregion

            var ctx1 = buildContext(server1);
            var ctx2 = buildContext(server2);
            var ctx3 = buildContext(server3);

            // region 2. 在各节点创建 Agent
            var create1Req = buildRequest("msg-1", JSONUtil.create().put("name", "agent-1"));
            var create1Resp = server1.handleUserEvent(ctx1, Server.EVENT_AGENT_CREATE, create1Req);
            var agentId1 = parseResponseBody(create1Resp).path("agentId").asText();

            var create2Req = buildRequest("msg-2", JSONUtil.create().put("name", "agent-2"));
            var create2Resp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_CREATE, create2Req);
            var agentId2 = parseResponseBody(create2Resp).path("agentId").asText();

            var create3Req = buildRequest("msg-3", JSONUtil.create().put("name", "agent-3"));
            var create3Resp = server3.handleUserEvent(ctx3, Server.EVENT_AGENT_CREATE, create3Req);
            var agentId3 = parseResponseBody(create3Resp).path("agentId").asText();

            assertFalse(agentId1.isEmpty());
            assertFalse(agentId2.isEmpty());
            assertFalse(agentId3.isEmpty());
            // endregion

            // region 3. 验证 Agent 分布
            assertEquals(server1.id, metaData.getAgentStay(agentId1));
            assertEquals(server2.id, metaData.getAgentStay(agentId2));
            assertEquals(server3.id, metaData.getAgentStay(agentId3));
            // endregion

            // region 4. 在 server2 deactivate agent2
            var deactivateReq = buildRequest("msg-deactivate", JSONUtil.create().put("agentId", agentId2));
            server2.handleUserEvent(ctx2, Server.EVENT_AGENT_DEACTIVATE, deactivateReq);
            assertFalse(server2.localAgents.containsKey(agentId2));
            assertNull(metaData.getAgentStay(agentId2));
            // endregion

            // region 5. 在 server2 上 activate agent2（从 NfsPersistence 恢复）
            var activateReq = buildRequest("msg-activate", JSONUtil.create().put("agentId", agentId2));
            var activateResp = server2.handleUserEvent(ctx2, Server.EVENT_AGENT_ACTIVATE, activateReq);
            assertNotNull(activateResp);
            assertTrue(server2.localAgents.containsKey(agentId2), "activate 后 Agent 应回到 server2");
            assertEquals(server2.id, metaData.getAgentStay(agentId2));
            // endregion

            // region 6. 创建 Team
            var teamCreateReq = buildRequest("msg-team-create", JSONUtil.create().put("name", "test-team"));
            var teamCreateResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_CREATE, teamCreateReq);
            var teamId = parseResponseBody(teamCreateResp).path("teamId").asText();
            assertFalse(teamId.isEmpty());
            assertTrue(server1.localTeams.containsKey(teamId));
            // endregion

            // region 7. 列出 Team
            var teamListReq = buildRequest("msg-team-list", JSONUtil.create().put("keyword", ""));
            var teamListResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_LIST, teamListReq);
            assertNotNull(teamListResp);
            assertEquals(1, parseResponseBody(teamListResp).path("teams").size());
            // endregion

            // region 8. 删除 Team
            var teamRemoveReq = buildRequest("msg-team-remove", JSONUtil.create().put("teamId", teamId));
            var teamRemoveResp = server1.handleUserEvent(ctx1, Server.EVENT_TEAM_REMOVE, teamRemoveReq);
            assertNotNull(teamRemoveResp);
            assertFalse(server1.localTeams.containsKey(teamId));
            // endregion

            // region 9. 关闭服务
            server1.stop();
            server2.stop();
            server3.stop();
            assertTrue(server1.isShuttingDown);
            assertTrue(server2.isShuttingDown);
            assertTrue(server3.isShuttingDown);
            assertTrue(metaData.getNodes().isEmpty(), "关闭后应无节点");
            // endregion
        }
    }

    // endregion
}
