package me.karboom.java.iSerf.server;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.Event;
import me.karboom.java.iSerf.agent.Message;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.persistence.AgentSnapshot;
import me.karboom.java.iSerf.agent.persistence.NonePersistence;
import me.karboom.java.iSerf.agent.tool.Tool;
import me.karboom.java.iSerf.llm.text.BatchTaskInfo;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.server.messageBus.MemoryMessageBus;
import me.karboom.java.iSerf.server.metaData.MemoryMetaData;
import me.karboom.java.iSerf.server.transport.NoneTransport;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ContainerServerTest {

    /** 轻量 Agent 工厂：FakeLlmProvider 返回 Flux.empty()，不依赖真实 LLM */
    static Agent makeAgent() {
        var provider = new ILlmProvider() {
            @Override
            public IText get(List<Tool<?>> tools, Class<?> outputFormat, Integer retryTimes) {
                return new IText() {
                    @Override
                    public Flux<Output> send(List<Message> memory, Class<?> of, List<Tool<?>> t) { return Flux.empty(); }
                    @Override
                    public Output query(List<Message> messages, Class<?> of) { return null; }
                    @Override
                    public String batch(List<List<Message>> mb, Class<?> of) { return null; }
                    @Override
                    public BatchTaskInfo taskStatus(String taskId) { return null; }
                    @Override
                    public List<Output> taskResult(BatchTaskInfo task) { return List.of(); }
                };
            }
        };
        return new Agent(UUID.randomUUID().toString(), "test", provider, List.of()) {
            @Override
            public void recovery() {
                this.trigger(Event.builder().type(Event.Type.RECOVERY).priority(0).build());
            }
        };
    }

    // ======================== 被测对象 & 依赖 ========================

    ContainerServer container;
    MemoryMessageBus messageBus;
    MemoryMetaData metaData;
    NoneTransport transport;
    NonePersistence persistence;

    @BeforeEach
    void setUp() {
        messageBus = new MemoryMessageBus();
        metaData = new MemoryMetaData();
        transport = new NoneTransport();
        persistence = new NonePersistence();

        container = new ContainerServer("127.0.0.1", messageBus, metaData,
                new AgentLifecycle() {
                    @Override
                    public Agent createAgent(ContainerServer.Context ctx, ObjectNode params) { return makeAgent(); }
                    @Override
                    public List<Agent> listAgent(ContainerServer.Context ctx, ObjectNode params) { return List.of(); }
                    @Override
                    public Agent removeAgent(ContainerServer.Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent editAgent(ContainerServer.Context ctx, ObjectNode params) { return null; }
                    @Override
                    public Agent detailAgent(ContainerServer.Context ctx, ObjectNode params) {
                        var id = params.path("agentId").asText();
                        return container.localAgents.get(id);
                    }
                    @Override
                    public ObjectNode serializeAgent(Agent agent) {
                        return JSONUtil.create().put("id", agent.metadata.getId());
                    }
                    @Override
                    public ObjectNode serializeAgentSnapshot(AgentSnapshot snapshot) {
                        return JSONUtil.convert(snapshot);
                    }
                },
                new TeamLifecycle() {
                    @Override
                    public Team createTeam(ContainerServer.Context ctx, ObjectNode params) {
                        return new Team(makeAgent(), List.of()) {};
                    }
                    @Override
                    public List<Team> listTeam(ContainerServer.Context ctx, ObjectNode params) { return List.of(); }
                    @Override
                    public Team removeTeam(ContainerServer.Context ctx, ObjectNode params) {
                        var teamId = params.path("teamId").asText();
                        return container.localTeams.get(teamId);
                    }
                    @Override
                    public Team editTeam(ContainerServer.Context ctx, ObjectNode params) {
                        return new Team(makeAgent(), List.of()) {};
                    }
                    @Override
                    public Team detailTeam(ContainerServer.Context ctx, ObjectNode params) {
                        var id = params.path("teamId").asText();
                        return container.localTeams.get(id);
                    }
                    @Override
                    public ObjectNode serializeTeam(Team team) {
                        return JSONUtil.create().put("id", "team-id");
                    }
                }) {
            @Override
            public void eventInterceptor(Context ctx, String event, String dataJson) {}
        };
        container.persistence = persistence;
        container.addTransport(transport);
    }

    private ContainerServer.Context buildContext(boolean isInternal) {
        return ContainerServer.Context.builder()
                .transport(transport)
                .client("test-client")
                .requestId("req-123")
                .isInternal(isInternal)
                .build();
    }

    // ======================== 构造与生命周期 ========================

    @Nested
    class Constructor {
        @Test
        void testConstructor() {
            assertNotNull(container.id);
            assertFalse(container.id.isEmpty());
            assertEquals("127.0.0.1", container.clusterIp);
            assertSame(messageBus, container.messageBus);
            assertSame(metaData, container.metaData);
        }
    }

    @Nested
    class AddTransport {
        @Test
        void testAddTransport() {
            var sizeBefore = container.transports.size();
            container.addTransport(new NoneTransport());
            assertEquals(sizeBefore + 1, container.transports.size());
        }
    }

    @Nested
    class RemoveTransport {
        @Test
        void testRemoveTransport() {
            var extra = new NoneTransport();
            container.addTransport(extra);
            var sizeBefore = container.transports.size();
            container.removeTransport(extra);
            assertEquals(sizeBefore - 1, container.transports.size());
        }
    }

    @Nested
    class Start {
        @Test @Timeout(5)
        void testStart() {
            container.start();
            var nodes = metaData.getNodes();
            assertTrue(nodes.stream().anyMatch(n -> n.getId().equals(container.id)));
            // start 已调用 listenMessage → 已订阅消息总线
        }
    }

    @Nested
    class Stop {
        @Test @Timeout(5)
        void testStop() {
            container.start();
            container.stop();
            assertTrue(container.isShuttingDown);
            var nodes = metaData.getNodes();
            assertTrue(nodes.stream().noneMatch(n -> n.getId().equals(container.id)));
        }
    }

    @Nested
    class StartStopLifecycle {
        @Test @Timeout(5)
        void testStartStopLifecycle() {
            container.start();
            var nodesAfterStart = metaData.getNodes();
            assertTrue(nodesAfterStart.stream().anyMatch(n -> n.getId().equals(container.id)));

            container.stop();
            assertTrue(container.isShuttingDown);
            var nodesAfterStop = metaData.getNodes();
            assertTrue(nodesAfterStop.stream().noneMatch(n -> n.getId().equals(container.id)));
        }
    }

    // ======================== Agent 事件处理 ========================

    @Nested
    class HandleAgentCreate {
        @Test @Timeout(5)
        void testHandleAgentCreate() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("name", "test-agent");

            var result = container.handleAgentCreate(ctx, data);

            assertNotNull(result);
            var agentId = result.path("agentId").asText();
            assertFalse(agentId.isEmpty());
            assertTrue(container.localAgents.containsKey(agentId));
            assertEquals(container.id, metaData.getAgentStay(agentId));
        }
    }

    @Nested
    class HandleAgentSend {
        @Test @Timeout(5)
        void testHandleAgentSendLocal() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var userMsg = Message.builder()
                    .role(Message.ROLE.USER).type(Message.TYPE.TEXT).text("hello").build();
            var eventNode = JSONUtil.convert(
                    Event.builder().type(Event.Type.MESSAGE).priority(1).message(userMsg).build());

            var ctx = buildContext(false);
            var sendData = JSONUtil.create().put("agentId", agentId).set("event", eventNode);

            var result = container.handleAgentSend(ctx, sendData);
            assertNull(result);
        }

        @Test @Timeout(5)
        void testHandleAgentSendRemote() {
            var ctx = buildContext(false);
            var nonExistentId = "non-existent-agent-send";
            metaData.setAgentStay(nonExistentId, "remote-node-1");

            var userMsg = Message.builder()
                    .role(Message.ROLE.USER).type(Message.TYPE.TEXT).text("hello").build();
            var data = JSONUtil.create()
                    .put("agentId", nonExistentId)
                    .set("event", JSONUtil.convert(
                            Event.builder().type(Event.Type.MESSAGE).priority(1).message(userMsg).build()));

            var result = container.handleAgentSend(ctx, data);
            assertNull(result);
        }
    }

    @Nested
    class HandleAgentSubscribe {
        @Test @Timeout(5)
        void testHandleAgentSubscribeLocal() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var ctx = buildContext(false);
            var subscribeData = JSONUtil.create().put("agentId", agentId);
            var result = container.handleAgentSubscribe(ctx, subscribeData);

            assertNull(result);
            assertTrue(container.localSubscriptions.containsKey(agentId));
        }

        @Test @Timeout(5)
        void testHandleAgentSubscribeRemote() {
            var ctx = buildContext(false);
            var remoteAgentId = "remote-agent-sub";
            metaData.setAgentStay(remoteAgentId, "remote-node-2");

            var subscribeData = JSONUtil.create().put("agentId", remoteAgentId);
            var result = container.handleAgentSubscribe(ctx, subscribeData);

            assertNull(result);
            assertTrue(container.agentClient.containsKey(remoteAgentId));
            assertEquals(transport.getTransportId(), container.agentClient.get(remoteAgentId).transportId);
        }
    }

    @Nested
    class HandleAgentUnsubscribe {
        @Test @Timeout(5)
        void testHandleAgentUnsubscribeLocal() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var ctx = buildContext(false);
            container.handleAgentSubscribe(ctx, JSONUtil.create().put("agentId", agentId));
            assertTrue(container.localSubscriptions.containsKey(agentId));

            var result = container.handleAgentUnsubscribe(ctx, JSONUtil.create().put("agentId", agentId));
            assertNull(result);
            assertFalse(container.localSubscriptions.containsKey(agentId));
        }

        @Test @Timeout(5)
        void testHandleAgentUnsubscribeRemote() {
            var ctx = buildContext(false);
            var remoteAgentId = "remote-agent-unsub-2";
            metaData.setAgentStay(remoteAgentId, "remote-node-3");

            var result = container.handleAgentUnsubscribe(ctx, JSONUtil.create().put("agentId", remoteAgentId));
            assertNull(result);
        }
    }

    @Nested
    class HandleAgentToolCall {
        @Test @Timeout(5)
        void testHandleAgentToolCallLocal() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var ctx = buildContext(false);
            var data = JSONUtil.create().put("agentId", agentId).put("toolCallId", "non-existent-call");

            assertThrows(RuntimeException.class, () -> {
                container.handleAgentToolCall(ctx, data);
            });
        }

        @Test @Timeout(5)
        void testHandleAgentToolCallRemote() {
            var ctx = buildContext(false);
            var remoteAgentId = "remote-agent-tool-2";
            metaData.setAgentStay(remoteAgentId, "remote-node-4");

            var data = JSONUtil.create().put("agentId", remoteAgentId).put("toolCallId", "some-call");
            var result = container.handleAgentToolCall(ctx, data);
            assertNull(result);
        }
    }

    @Nested
    class HandleAgentList {
        @Test @Timeout(5)
        void testHandleAgentList() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("keyword", "test");
            var result = container.handleAgentList(ctx, data);

            assertNotNull(result);
            var agents = result.path("agents");
            assertFalse(agents.isMissingNode());
            assertTrue(agents.isArray());
            assertEquals(0, agents.size());
        }
    }

    @Nested
    class HandleAgentEdit {
        @Test @Timeout(5)
        void testHandleAgentEdit() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("agentId", "any-id");
            var result = container.handleAgentEdit(ctx, data);
            assertNull(result);
        }
    }

    @Nested
    class HandleAgentDetail {
        @Test @Timeout(5)
        void testHandleAgentDetailLocal() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var ctx = buildContext(false);
            var data = JSONUtil.create().put("agentId", agentId);
            var result = container.handleAgentDetail(ctx, data);

            assertNotNull(result);
            assertEquals(agentId, result.path("id").asText());
        }

        @Test @Timeout(5)
        void testHandleAgentDetailRemote() {
            var ctx = buildContext(false);
            var remoteAgentId = "remote-agent-detail-2";
            metaData.setAgentStay(remoteAgentId, "remote-node-5");

            var data = JSONUtil.create().put("agentId", remoteAgentId);
            var result = container.handleAgentDetail(ctx, data);

            assertNull(result);
            assertTrue(container.agentClient.containsKey(remoteAgentId));
        }
    }

    // ======================== Team 事件处理 ========================

    @Nested
    class HandleTeamCreate {
        @Test @Timeout(5)
        void testHandleTeamCreate() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("name", "test-team");
            var result = container.handleTeamCreate(ctx, data);

            assertNotNull(result);
            var teamId = result.path("teamId").asText();
            assertFalse(teamId.isEmpty());
            assertTrue(container.localTeams.containsKey(teamId));
            assertEquals(container.id, metaData.getTeamStay(teamId));
        }
    }

    @Nested
    class HandleTeamList {
        @Test @Timeout(5)
        void testHandleTeamList() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("keyword", "test");
            var result = container.handleTeamList(ctx, data);

            assertNotNull(result);
            assertFalse(result.path("teams").isMissingNode());
        }
    }

    @Nested
    class HandleTeamEdit {
        @Test @Timeout(5)
        void testHandleTeamEdit() {
            var ctx = buildContext(false);
            var data = JSONUtil.create().put("teamId", "any-team-id");
            var result = container.handleTeamEdit(ctx, data);

            assertNotNull(result);
            assertEquals("team-id", result.path("id").asText());
        }
    }

    @Nested
    class HandleTeamDetail {
        @Test @Timeout(5)
        void testHandleTeamDetailLocal() {
            var ctx = buildContext(false);
            var createResult = container.handleTeamCreate(ctx, JSONUtil.create());
            var teamId = createResult.path("teamId").asText();

            var data = JSONUtil.create().put("teamId", teamId);
            var result = container.handleTeamDetail(ctx, data);

            assertNotNull(result);
            assertEquals("team-id", result.path("id").asText());
        }

        @Test @Timeout(5)
        void testHandleTeamDetailRemote() {
            var ctx = buildContext(false);
            var remoteTeamId = "remote-team-detail-2";
            metaData.setTeamStay(remoteTeamId, "remote-node-6");

            var data = JSONUtil.create().put("teamId", remoteTeamId);
            var result = container.handleTeamDetail(ctx, data);

            assertNull(result);
            assertTrue(container.teamClient.containsKey(remoteTeamId));
        }
    }

    @Nested
    class HandleTeamRemove {
        @Test @Timeout(5)
        void testHandleTeamRemove() {
            var ctx = buildContext(false);
            var createResult = container.handleTeamCreate(ctx, JSONUtil.create());
            var teamId = createResult.path("teamId").asText();
            assertTrue(container.localTeams.containsKey(teamId));

            var data = JSONUtil.create().put("teamId", teamId);
            var result = container.handleTeamRemove(ctx, data);

            assertNotNull(result);
            assertEquals(teamId, result.path("teamId").asText());
            assertFalse(container.localTeams.containsKey(teamId));
            assertNull(metaData.getTeamStay(teamId));
        }
    }

    // ======================== handleUserEvent ========================

    @Nested
    class HandleUserEvent {
        @Test @Timeout(5)
        void testShuttingDown() {
            container.isShuttingDown = true;

            var ctx = buildContext(false);
            var dataJson = "{\"msgId\":\"msg-1\",\"body\":{}}";

            var result = container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, dataJson);

            assertNotNull(result);
            assertTrue(result.contains("\"error\""));
        }

        @Test @Timeout(5)
        void testMissingMsgId() {
            var ctx = buildContext(false);
            var dataJson = "{\"body\":{}}";

            var result = container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, dataJson);
            assertNotNull(result);
            assertTrue(result.contains("\"error\""));
        }

        @Test @Timeout(5)
        void testDispatch() {
            var ctx = buildContext(false);
            var dataJson = "{\"msgId\":\"msg-dispatch\",\"body\":{}}";

            var result = container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, dataJson);

            assertNotNull(result);
            assertTrue(result.contains("agentId"));
        }

        @Test @Timeout(5)
        void testNonInternalResponse() {
            var ctx = buildContext(false);
            var dataJson = "{\"msgId\":\"msg-non-internal\",\"body\":{}}";

            var result = container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_CREATE, dataJson);

            assertNotNull(result);
            assertTrue(result.contains("agentId"));
        }

        @Test @Timeout(5)
        void testException() {
            var agent = makeAgent();
            var agentId = agent.metadata.getId();
            container.localAgents.put(agentId, agent);

            var ctx = buildContext(false);
            var data = JSONUtil.create().put("agentId", agentId).put("toolCallId", "non-existent");
            var body = new ContainerServer.Message<ObjectNode>() {};
            body.msgId = "msg-err";
            body.body = data;
            var dataJson = JSONUtil.stringify(body);

            var result = container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_TOOL_CALL, dataJson);

            assertNotNull(result);
            assertTrue(result.contains("\"error\""));
        }
    }

    // ======================== listenMessage ========================

    @Nested
    class ListenMessage {
        @BeforeEach
        void setUpListen() {
            container.start();
        }

        @Test @Timeout(5)
        void testProxy() {
            var sourceNodeId = "remote-node-proxy";

            var requestBody = "{\"msgId\":\"proxy-msg\",\"body\":{}}";
            var proxyArray = JSONUtil.createArray();
            proxyArray.add(sourceNodeId).add("proxy")
                    .add(ContainerServer.EVENT_AGENT_CREATE).add(requestBody);

            messageBus.publish("%s-message".formatted(container.id), JSONUtil.stringify(proxyArray));

            assertFalse(container.localAgents.isEmpty(), "proxy 处理后应创建 Agent");
        }

        @Test @Timeout(5)
        void testReverseAgentMessage() {
            var agentId = "reverse-agent";
            container.agentClient.put(agentId,
                    ContainerServer.TransportClientRecord.builder()
                            .transportId(transport.getTransportId()).clientHandle("client-123").build());

            var dataNode = JSONUtil.create().put("agentId", agentId);
            var bodyObj = new ContainerServer.OutMessageBody() {};
            bodyObj.setData(dataNode);
            var bodyJson = JSONUtil.stringify(bodyObj);

            var reverseArray = JSONUtil.createArray();
            reverseArray.add("remote-node").add("reverse")
                    .add(ContainerServer.EVENT_AGENT_MESSAGE).add(bodyJson);

            messageBus.publish("%s-message".formatted(container.id), JSONUtil.stringify(reverseArray));

            // verify 不抛异常即可（NoneTransport.sendToClient 是 no-op）
        }

        @Test @Timeout(5)
        void testReverseAgentDetail() {
            var agentId = "detail-agent-reverse";
            container.agentClient.put(agentId,
                    ContainerServer.TransportClientRecord.builder()
                            .transportId(transport.getTransportId()).clientHandle("detail-client").build());

            var dataNode = JSONUtil.create().set("metadata", JSONUtil.create().put("id", agentId));
            var bodyNode = JSONUtil.create().set("data", dataNode);
            var fullResponse = JSONUtil.create().set("body", bodyNode);

            var reverseArray = JSONUtil.createArray();
            reverseArray.add("remote-node").add("reverse")
                    .add(ContainerServer.EVENT_AGENT_DETAIL).add(JSONUtil.stringify(fullResponse));

            messageBus.publish("%s-message".formatted(container.id), JSONUtil.stringify(reverseArray));

            assertFalse(container.agentClient.containsKey(agentId), "推送后应从 agentClient 移除缓存");
        }

        @Test @Timeout(5)
        void testReverseTeamDetail() {
            var teamId = "detail-team-reverse";
            container.teamClient.put(teamId,
                    ContainerServer.TransportClientRecord.builder()
                            .transportId(transport.getTransportId()).clientHandle("team-detail-client").build());

            var dataNode = JSONUtil.create().set("metadata", JSONUtil.create().put("id", teamId));
            var bodyNode = JSONUtil.create().set("data", dataNode);
            var fullResponse = JSONUtil.create().set("body", bodyNode);

            var reverseArray = JSONUtil.createArray();
            reverseArray.add("remote-node").add("reverse")
                    .add(ContainerServer.EVENT_TEAM_DETAIL).add(JSONUtil.stringify(fullResponse));

            messageBus.publish("%s-message".formatted(container.id), JSONUtil.stringify(reverseArray));

            assertFalse(container.teamClient.containsKey(teamId), "推送后应从 teamClient 移除缓存");
        }
    }
}
