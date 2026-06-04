package me.karboom.java.iSerf.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.Event;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.Node;
import me.karboom.java.iSerf.server.transport.ITransport;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用传输容器，承载协议无关的元数据管理和消息总线逻辑。
 * 可挂载多个 ITransport 实现（Websocket、Mqtt 等），统一管理生命周期和集群通信。
 */
@Slf4j
public abstract class ContainerServer {

    /**
     * 关闭状态下的拒绝消息
     */
    private static final String SHUTTING_DOWN_ERROR = "{\"error\":\"server is shutting down\"}";

    // ======================== 事件名常量 ========================
    public static final String EVENT_AGENT_CREATE = "agent.create";
    public static final String EVENT_AGENT_SEND = "agent.send";
    public static final String EVENT_AGENT_SUBSCRIBE = "agent.subscribe";
    public static final String EVENT_AGENT_UNSUBSCRIBE = "agent.unsubscribe";
    public static final String EVENT_AGENT_TOOL_CALL = "agent.toolCall";
    public static final String EVENT_AGENT_LIST = "agent.list";
    public static final String EVENT_AGENT_MESSAGE = "agent.message";

    /**
     * 客户端记录：存储 transport 标识和客户端句柄，支持多协议互通
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class TransportClientRecord {
        /**
         * 所属 transport 的唯一标识
         */
        public String transportId;
        /**
         * transport 特定的客户端句柄（SocketIOClient / mqtt client / etc.）
         */
        public Object clientHandle;
    }

    /**
     * Agent 列表查询的聚合收集器，用于异步收集多节点查询结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class ListQueryCollector {
        /**
         * 请求唯一标识
         */
        public String requestId;
        /**
         * 期望响应的节点数
         */
        public Integer expectedCount;
        /**
         * 已收集的结果列表
         */
        public List<ObjectNode> results;
        /**
         * 发起查询的原始上下文（用于回传结果给客户端）
         */
        public Context originalContext;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Context {
        public ITransport transport;
        /**
         * 请求ID,用于异步回复的匹配
         */
        public String requestId;

        public Object client;

        public Object user;
        /**
         * 消息来源服务器标识
         */
        public String serverId;
    }

    /**
     * 挂载的所有传输协议实例
     */
    public final List<ITransport> transports = new ArrayList<>();

    /**
     * 本节点 ID
     */
    public String id;

    public String clusterIp;

    /**
     * 消息总线，用于集群节点间通信
     */
    public IMessageBus messageBus;

    /**
     * 元数据存储，管理节点和 Agent 路由信息
     */
    public IMetaData metaData;

    /**
     * 本地 Agent 缓存
     */
    public Map<String, Agent> localAgents = new ConcurrentHashMap<>();

    /**
     * client 缓存：AgentId 对应 TransportClientRecord（多协议互通）
     */
    public Map<String, TransportClientRecord> agentClient = new ConcurrentHashMap<>();

    /**
     * Agent 列表查询聚合收集器缓存：requestId 对应 ListQueryCollector
     */
    public Map<String, ListQueryCollector> listQueryCollectors = new ConcurrentHashMap<>();

    /**
     * 关闭状态标志
     */
    public volatile boolean isShuttingDown = false;

    /**
     * 构造通用传输容器
     *
     * @param messageBus 消息总线
     * @param metaData   元数据存储
     */
    public ContainerServer(String clusterIp, IMessageBus messageBus, IMetaData metaData) {
        this.clusterIp = clusterIp;
        this.id = DataUtil.getFlakeId();
        this.messageBus = messageBus;
        this.metaData = metaData;
    }

    /**
     * Agent 工厂方法，由子类实现
     *
     * @param params Agent 构造参数
     * @return Agent 实例
     */
    protected abstract Agent createAgent(Context ctx, ObjectNode params);

    /**
     * 按照条件查找智能体列表
     * @param ctx
     * @param params
     * @return 匹配的Agent列表
     */
    protected abstract List<Agent> listAgent(Context ctx, ObjectNode params);

    /**
     * 删除Agent
     * @param ctx
     * @param params
     * @return
     */
    protected abstract Agent removeAgent(Context ctx, ObjectNode params);


    /**
     * 挂载一个传输协议实例
     *
     * @param transport 传输协议实例
     */
    public void addTransport(ITransport transport) {
        transports.add(transport);
        log.debug("addTransport transport added, total: %s".formatted(transports.size()));
    }

    /**
     * 移除一个传输协议实例
     *
     * @param transport 传输协议实例
     */
    public void removeTransport(ITransport transport) {
        transports.remove(transport);
        log.debug("removeTransport transport removed, total: %s".formatted(transports.size()));
    }

    /**
     * 启动所有已挂载的传输协议，并在启动后注册元数据和监听消息总线
     */
    public void start() {
        log.debug("start starting %s transports".formatted(transports.size()));

        // 注册JVM关闭钩子，监听SIGTERM/SIGINT信号
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.debug("start Received shutdown signal, setting isShuttingDown to true");
            isShuttingDown = true;
        }, "container-shutdown-hook"));

        transports.forEach(transport -> {
            log.debug("start starting transport: %s".formatted(transport.getClass().getSimpleName()));
            transport.start();
        });

        metaData.addNode(Node.builder().id(this.id).port(0).ip(this.clusterIp).build());

        this.listenMessage();

        log.debug("start all transports started");
    }

    /**
     * 停止所有已挂载的传输协议，并移除元数据
     */
    public void stop() {
        isShuttingDown = true;
        metaData.removeNode(this.id);

        log.debug("stop stopping %s transports".formatted(transports.size()));
        transports.forEach(transport -> {
            log.debug("stop stopping transport: %s".formatted(transport.getClass().getSimpleName()));
            transport.stop();
        });
        log.debug("stop all transports stopped");
    }

    // ======================== Agent 事件处理 ========================

    /**
     * 处理 agent.create 事件：创建 Agent
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentCreate(Context context, ObjectNode data) {
        var agent = createAgent(context, data);
        var agentId = agent.id;
        localAgents.put(agentId, agent);
        metaData.setAgentStay(agentId, this.id);
        log.debug("handleAgentCreate agent.create: local " + JSONUtil.stringify(data));
        return JSONUtil.create().put("agentId", agentId);
    }

    /**
     * 处理 agent.send 事件：发送消息给 Agent
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentSend(Context context, ObjectNode data) {
        var agentId = data.path("agentId").asText();
        var eventObj = JSONUtil.convert(data.path("event"), Event.class);
        var agent = localAgents.get(agentId);
        if (agent != null) {
            agent.trigger(eventObj);
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_SEND, JSONUtil.stringify(data), targetNodeId);
        }
        return null;
    }

    /**
     * 处理 agent.subscribe 事件：订阅 Agent 消息流
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentSubscribe(Context context, ObjectNode data) {
        var agentId = data.path("agentId").asText();
        var agent = localAgents.get(agentId);

        if (agent != null) {
            agent.subscribe(item -> {
                var payload = JSONUtil.create()
                        .put("agentId", agentId)
                        .set("message", JSONUtil.convert(item));
                var wrapped = JSONUtil.create()
                        .put("requestId", context.requestId)
                        .set("data", payload);
                var messageJson = JSONUtil.stringify(wrapped);

                    if (this.id.equals(context.serverId)) {
                        if (context.transport != null) {
                            context.transport.sendToClient(context.client, EVENT_AGENT_MESSAGE, messageJson);
                        } else {
                            log.warn("handleAgentSubscribe no transport for client: %s".formatted(context.client));
                        }
                    } else {
                        var array = JSONUtil.createArray();
                        array.add(this.id).add("reverse").add(EVENT_AGENT_MESSAGE).add(messageJson);
                        messageBus.publish("%s-message".formatted(context.serverId), JSONUtil.stringify(array));
                    }
            });
            return null;
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
                    var transportRecord = TransportClientRecord.builder()
                            .transportId(context.transport.getTransportId())
                            .clientHandle(context.client)
                            .build();
                    agentClient.put(agentId, transportRecord);
            sendToOtherNode(EVENT_AGENT_SUBSCRIBE, JSONUtil.stringify(data), targetNodeId);
            return null;
        }
    }

    /**
     * 处理 agent.unsubscribe 事件：取消订阅 Agent
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentUnsubscribe(Context context, ObjectNode data) {
        var agentId = data.path("agentId").asText();
        var agent = localAgents.get(agentId);
        if (agent != null) {
            agentClient.remove(agentId);
            return null;
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_UNSUBSCRIBE, JSONUtil.stringify(data), targetNodeId);
            return null;
        }
    }

    /**
     * 处理 agent.toolCall 事件：执行工具调用
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentToolCall(Context context, ObjectNode data) {
        var agentId = data.path("agentId").asText();
        var toolCallId = data.path("toolCallId").asText();
        var agent = localAgents.get(agentId);
        log.debug("handleAgentToolCall agentId: " + agentId + ", toolCallId: " + toolCallId);
        if (agent != null) {
            var result = agent.invokeToolCallCache(toolCallId);
            return (ObjectNode) JSONUtil.convert(result);
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_TOOL_CALL, JSONUtil.stringify(data), targetNodeId);
            return null;
        }
    }

    /**
     * 按照条件查找智能体，同时从自身和其他节点查询，然后合并
     * 具体的匹配逻辑委托抽象方法listAgent
     * 最终通过同名事件响应
     *
     * @param context
     * @param data
     * @return null
     */
    public ObjectNode handleAgentList(Context context, ObjectNode data) {
        var agents = listAgent(context, data);

        // region 代理调用：其他节点查询本节点Agent，直接返回结果
        if (!this.id.equals(context.serverId)) {
            var resultArray = JSONUtil.createArray();
            agents.forEach(agent -> resultArray.add(JSONUtil.convert(agent)));
            return JSONUtil.create().set("agents", resultArray);
        }
        // endregion

        var allNodes = metaData.getNodes();
        var otherNodes = allNodes.stream()
                .filter(node -> !this.id.equals(node.id))
                .toList();

        // region 本节点调用 + 无其他节点：直接同步返回
        if (otherNodes.isEmpty()) {
            var resultArray = JSONUtil.createArray();
            agents.forEach(agent -> resultArray.add(JSONUtil.convert(agent)));
            return JSONUtil.create().set("agents", resultArray);
        }
        // endregion

        // region 本节点调用 + 有其他节点：广播查询，异步聚合
        var requestId = context.requestId;
        var localResults = new ArrayList<ObjectNode>();
        agents.forEach(agent -> localResults.add((ObjectNode) JSONUtil.convert(agent)));

        var collector = ListQueryCollector.builder()
                .requestId(requestId)
                .expectedCount(otherNodes.size())
                .results(localResults)
                .originalContext(context)
                .build();
        listQueryCollectors.put(requestId, collector);

        otherNodes.forEach(node -> sendToOtherNode(EVENT_AGENT_LIST, JSONUtil.stringify(data), node.id));
        return null;
        // endregion
    }

    /**
     * 处理用户事件
     * 非本节点（serverId != this.id）的 proxy 请求直接返回对应值；
     * 本节点请求若有返回值，通过 transport 派发同名事件
     *
     * @param context  上下文（含 transport、client、user）
     * @param event    事件名
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleUserEvent(Context context, String event, String dataJson) {
        try {
            if (isShuttingDown) {
                throw new RuntimeException("server is shutting down");
            }
            var data = JSONUtil.parse(dataJson);
            if (!data.has("requestId")) {
                throw new RuntimeException("need requestId");
            }

            var requestId = data.get("requestId").asString();
            context.setRequestId(requestId);

            var result = switch (event) {
                case EVENT_AGENT_CREATE -> handleAgentCreate(context, data);
                case EVENT_AGENT_SEND -> handleAgentSend(context, data);
                case EVENT_AGENT_SUBSCRIBE -> handleAgentSubscribe(context, data);
                case EVENT_AGENT_UNSUBSCRIBE -> handleAgentUnsubscribe(context, data);
                case EVENT_AGENT_TOOL_CALL -> handleAgentToolCall(context, data);
                case EVENT_AGENT_LIST -> handleAgentList(context, data);
                default -> null;
            };
            var wrapped = JSONUtil.create().set("data", result).put("requestId", requestId);

            if (result != null && this.id.equals(context.serverId)) {
                context.transport.sendToClient(context.client, event, JSONUtil.stringify(wrapped));
            }
            return result != null ? JSONUtil.stringify(wrapped) : null;
        } catch (Exception e) {
            var result = JSONUtil.create().put("error", e.getMessage().toString());

            return JSONUtil.stringify(result);
        }

    }

    /**
     * 检查是否正在关闭
     *
     * @return true 表示正在关闭
     */
    public boolean isShuttingDown() {
        return isShuttingDown;
    }

    // ======================== 集群通信 ========================

    /**
     * 通过MessageBus发布消息给其他节点
     * 消息主题为 nodeId-message，消息数据结构为 JSON 数组：[sourceNodeId, type, event, body]
     */
    private void sendToOtherNode(String event, String data, String nodeId) {
        var array = JSONUtil.createArray();
        array.add(this.id).add("proxy").add(event).add(data);
        this.messageBus.publish("%s-message".formatted(nodeId), JSONUtil.stringify(array));
    }

    /**
     * 订阅需要本节点处理的消息
     * 消息主题为 nodeId-message，消息数据结构为 JSON 数组：[sourceNodeId, type, event, body]
     */
    public void listenMessage() {
        messageBus.subscribe("%s-message".formatted(this.id), (message) -> {
            try {
                log.debug("listenMessage message: " + message);
                var parts = JSONUtil.parseArray(message);

                var sourceNode = parts.get(0).asText();
                var type = parts.get(1).asText();
                var event = parts.get(2).asText();
                var bodyJson = parts.get(3).asText();


                switch (type) {
                    case "proxy" -> {

                        var proxyCtx = Context.builder().client(sourceNode).serverId(sourceNode).build();
                        var result = handleUserEvent(proxyCtx, event, bodyJson);
                        log.debug("listenMessage proxy event: " + event + ", result: " + result);

                        if (result != null) {
                            var array = JSONUtil.createArray();
                            array.add(this.id).add("reverse").add(event).add(result);
                            messageBus.publish("%s-message".formatted(sourceNode), JSONUtil.stringify(array));
                        }
                    }
                    case "reverse" -> {

                        if (EVENT_AGENT_LIST.equals(event)) {
                            // region 聚合 agent.list 各节点查询结果
                            var ackData = JSONUtil.parse(bodyJson);
                            var wrappedData = ackData.path("data");
                            if (!wrappedData.isMissingNode() && wrappedData.has("agents")) {
                                var ackRequestId = ackData.path("requestId").asText();
                                var collector = listQueryCollectors.get(ackRequestId);
                                if (collector != null) {
                                    var agentsArray = wrappedData.path("agents");
                                    if (agentsArray.isArray()) {
                                        agentsArray.forEach(item ->
                                                collector.results.add((ObjectNode) item));
                                    }
                                    collector.expectedCount--;
                                    if (collector.expectedCount <= 0) {
                                        var mergedArray = JSONUtil.createArray();
                                        collector.results.forEach(mergedArray::add);
                                        var combined = JSONUtil.create()
                                                .put("requestId", ackRequestId)
                                                .set("data", mergedArray);
                                        var ctx = collector.originalContext;
                                        ctx.transport.sendToClient(ctx.client, event, JSONUtil.stringify(combined));
                                        listQueryCollectors.remove(ackRequestId);
                                    }
                                }
                            }
                            // endregion
                        } else {
                            // region 处理 agent 消息推送：通过 transport 发送给订阅客户端
                            var data = JSONUtil.parse(bodyJson);
                            var agentId = data.path("data").path("agentId").asText();
                            var record = agentClient.get(agentId);
                            if (record != null) {
                                for (var transport : transports) {
                                    if (record.getTransportId().equals(transport.getTransportId())) {
                                        transport.sendToClient(record.getClientHandle(), event, bodyJson);
                                        break;
                                    }
                                }
                            }
                            // endregion
                        }
                    }
                }
            } catch (Exception e) {
               log.error("<listenMessage>", e);
            }
        });
    }
}