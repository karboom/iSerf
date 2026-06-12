package me.karboom.java.iSerf.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.Event;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.Node;
import me.karboom.java.iSerf.server.transport.ITransport;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import reactor.core.Disposable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用传输容器，承载协议无关的元数据管理和消息总线逻辑。
 * 可挂载多个 ITransport 实现（Websocket、Mqtt 等），统一管理生命周期和集群通信。
 */
@Slf4j
public abstract class ContainerServer {

    // ======================== 事件名常量 ========================
    public static final String EVENT_AGENT_CREATE = "agent.create";
    public static final String EVENT_AGENT_SEND = "agent.send";
    public static final String EVENT_AGENT_SUBSCRIBE = "agent.subscribe";
    public static final String EVENT_AGENT_UNSUBSCRIBE = "agent.unsubscribe";
    public static final String EVENT_AGENT_TOOL_CALL = "agent.toolCall";
    public static final String EVENT_AGENT_LIST = "agent.list";
    public static final String EVENT_AGENT_MESSAGE = "agent.message";
    public static final String EVENT_AGENT_EDIT = "agent.edit";
    public static final String EVENT_AGENT_DETAIL = "agent.detail";
    public static final String EVENT_TEAM_CREATE = "team.create";
    public static final String EVENT_TEAM_LIST = "team.list";
    public static final String EVENT_TEAM_REMOVE = "team.remove";
    public static final String EVENT_TEAM_EDIT = "team.edit";
    public static final String EVENT_TEAM_DETAIL = "team.detail";


    static class Error extends RuntimeException {

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message<T> {
        public String msgId;

        public T body;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutMessageBody {
        public String error;

        public ObjectNode data;
    }


    /**
     * 客户端记录：存储 transport 标识和客户端句柄，支持多协议互通
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransportClientRecord {
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
    public static class ListQueryCollector {
        /**
         * 请求唯一标识
         */
        public String requestId;
        /**
         * 期望响应的节点数
         */
        public AtomicInteger expectedCount;
        /**
         * 已收集的结果列表
         */
        public List<ObjectNode> results;
        /**
         * 发起查询的原始上下文（用于回传结果给客户端）
         */
        public Context originalContext;
        /**
         * 创建时间戳，用于超时清理
         */
        public long createTime;
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

        public Object userData;
        /**
         * 用户标识
         */
        public String userId;
        /**
         * 请求原始数据
         */
        public ObjectNode requestData;
        /**
         * 消息来源服务器标识
         */
        public String serverId;
        /**
         * 所属容器实例，供生命周期方法访问 messageBus、metaData 等
         */
        public ContainerServer server;
        /**
         * 是否集群内部通信（来自其他节点的代理请求）
         */
        public Boolean isInternal;
    }

    /**
     * 挂载的所有传输协议实例
     */
    public final List<ITransport> transports = new CopyOnWriteArrayList<>();

    /**
     * 本节点 ID
     */
    public String id;

    /**
     * 集群通信地址（IP），用于注册节点时上报
     */
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
     * Agent client 缓存：AgentId 对应 TransportClientRecord（多协议互通，用于远程订阅场景）
     */
    public Map<String, TransportClientRecord> agentClient = new ConcurrentHashMap<>();

    /**
     * Team client 缓存：TeamId 对应 TransportClientRecord（多协议互通，用于远程 detail 场景）
     */
    public Map<String, TransportClientRecord> teamClient = new ConcurrentHashMap<>();

    /**
     * 本地订阅缓存：AgentId 对应 Disposable，用于取消本地订阅
     */
    public Map<String, Disposable> localSubscriptions = new ConcurrentHashMap<>();

    /**
     * Agent 列表查询聚合收集器缓存：requestId 对应 ListQueryCollector
     */
    public Map<String, ListQueryCollector> listQueryCollectors = new ConcurrentHashMap<>();

    /**
     * 本地 Team 缓存
     */
    public Map<String, Team> localTeams = new ConcurrentHashMap<>();

    /**
     * Agent 生命周期管理
     */
    public AgentLifecycle agentLifecycle;

    /**
     * Team 生命周期管理
     */
    public TeamLifecycle teamLifecycle;

    /**
     * 关闭状态标志
     */
    public volatile boolean isShuttingDown = false;

    /**
     * 构造通用传输容器
     *
     * @param clusterIp      集群通信地址
     * @param messageBus     消息总线
     * @param metaData       元数据存储
     * @param agentLifecycle Agent 生命周期管理
     * @param teamLifecycle  Team 生命周期管理
     */
    public ContainerServer(String clusterIp, IMessageBus messageBus, IMetaData metaData,
                           AgentLifecycle agentLifecycle, TeamLifecycle teamLifecycle) {
        this.clusterIp = clusterIp;
        this.id = DataUtil.getFlakeId();
        this.messageBus = messageBus;
        this.metaData = metaData;
        this.agentLifecycle = agentLifecycle;
        this.teamLifecycle = teamLifecycle;
    }

    public abstract void eventInterceptor(Context ctx, String event, String dataJson);
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
        var agent = agentLifecycle.createAgent(context, data).run();
        var agentId = agent.metadata.getId();
        localAgents.put(agentId, agent);
        metaData.setAgentStay(agentId, this.id);
        log.debug("handleAgentCreate agent.create: local %s".formatted(JSONUtil.stringify(data)));
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
     * 处理 agent.subscribe 事件：订阅 Agent 消息流。
     * Agent 在本地则直接注册订阅回调，通过 transport 或 messageBus 回推消息给客户端。
     * Agent 不在本地则将订阅请求转发到目标节点，同时缓存 TransportClientRecord 到 agentClient，
     * 供后续集群间 reverse 消息回传时定位客户端连接。
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据（含 agentId）
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentSubscribe(Context context, ObjectNode data) {
        var agentId = data.path("agentId").asText();
        var agent = localAgents.get(agentId);

        if (agent != null) {
            var disposable = agent.subscribe(item -> {
                var payload = JSONUtil.create()
                        .put("agentId", agentId)
                        .set("message", JSONUtil.convert(item));
                var messageMsg = new Message<OutMessageBody>() {};
                messageMsg.setMsgId(context.requestId);
                var subscribeBody = new OutMessageBody() {};
                subscribeBody.setData(payload);
                messageMsg.setBody(subscribeBody);
                var messageJson = JSONUtil.stringify(messageMsg);

                if (!context.isInternal) {
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
            localSubscriptions.put(agentId, disposable);
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
            var disposable = localSubscriptions.remove(agentId);
            if (disposable != null) {
                disposable.dispose();
            }
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
        log.debug("handleAgentToolCall agentId: %s, toolCallId: %s".formatted(agentId, toolCallId));
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
     * 按照条件查找智能体，根据请求来源分三个分支处理：
     * 1. 代理调用分支（非本节点请求）：直接返回本节点匹配的 Agent 列表
     * 2. 单节点分支（本节点请求且无其他节点）：同步返回匹配结果
     * 3. 多节点分支（本节点请求且有其他节点）：广播查询到其他节点，
     * 通过 ListQueryCollector 异步聚合各节点结果后，由 transport 回传客户端
     *
     * @param context 上下文（含 transport、client、user、container）
     * @param data    查询条件
     * @return 分支1、2 返回包含 agents 数组的 ObjectNode；分支3 返回 null（异步回传）
     */
    public ObjectNode handleAgentList(Context context, ObjectNode data) {
        var agents = agentLifecycle.listAgent(context, data);

        // region 代理调用：其他节点查询本节点Agent，直接返回结果
        if (context.isInternal) {
            var resultArray = JSONUtil.createArray();
            agents.forEach(agent -> resultArray.add(agentLifecycle.serializeAgent(agent)));
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
            agents.forEach(agent -> resultArray.add(agentLifecycle.serializeAgent(agent)));
            return JSONUtil.create().set("agents", resultArray);
        }
        // endregion

        // region 本节点调用 + 有其他节点：广播查询，异步聚合
        var requestId = context.requestId;
        var localResults = new ArrayList<ObjectNode>();
        agents.forEach(agent -> localResults.add(agentLifecycle.serializeAgent(agent)));

        var collector = ListQueryCollector.builder()
                .requestId(requestId)
                .originalContext(context)
                .createTime(System.currentTimeMillis())
                .build();
        collector.expectedCount = new AtomicInteger(otherNodes.size());
        collector.results = Collections.synchronizedList(new ArrayList<>(localResults));
        listQueryCollectors.put(requestId, collector);
        cleanupExpiredCollectors();

        otherNodes.forEach(node -> sendToOtherNode(EVENT_AGENT_LIST, JSONUtil.stringify(data), node.id));
        return null;
        // endregion
    }

    /**
     * 处理 agent.edit 事件：编辑 Agent
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据（含 agentId 及要修改的字段）
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleAgentEdit(Context context, ObjectNode data) {
        var agent = agentLifecycle.editAgent(context, data);
        if (agent != null) {
            return agentLifecycle.serializeAgent(agent);
        }
        return null;
    }

    /**
     * 处理 agent.detail 事件：获取 Agent 详情。
     * Agent 在本地则直接返回详情，不在本地则代理到目标节点。
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据（含 agentId）
     * @return 响应 ObjectNode，本地命中时返回；远程代理时返回 null（异步回调）
     */
    public ObjectNode handleAgentDetail(Context context, ObjectNode data) {
        var agent = agentLifecycle.detailAgent(context, data);
        if (agent != null) {
            return agentLifecycle.serializeAgent(agent);
        }
        var agentId = data.path("agentId").asText();
        var targetNodeId = metaData.getAgentStay(agentId);
        if (targetNodeId != null && !this.id.equals(targetNodeId)) {
            var transportRecord = TransportClientRecord.builder()
                    .transportId(context.transport.getTransportId())
                    .clientHandle(context.client)
                    .build();
            agentClient.put(agentId, transportRecord);
            sendToOtherNode(EVENT_AGENT_DETAIL, JSONUtil.stringify(data), targetNodeId);
        }
        return null;
    }

    // ======================== Team 事件处理 ========================

    /**
     * 处理 team.create 事件：创建 Team
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleTeamCreate(Context context, ObjectNode data) {
        var team = teamLifecycle.createTeam(context, data);
        var teamId = DataUtil.getFlakeId();
        localTeams.put(teamId, team);
        metaData.setTeamStay(teamId, this.id);
        log.debug("handleTeamCreate team.create: local %s".formatted(JSONUtil.stringify(data)));
        return JSONUtil.create().put("teamId", teamId);
    }

    /**
     * 按照条件查找 Team，根据请求来源分三个分支处理：
     * 1. 代理调用分支（非本节点请求）：直接返回本节点匹配的 Team 列表
     * 2. 单节点分支（本节点请求且无其他节点）：同步返回匹配结果
     * 3. 多节点分支（本节点请求且有其他节点）：广播查询到其他节点，
     * 通过 ListQueryCollector 异步聚合各节点结果后，由 transport 回传客户端
     *
     * @param context 上下文（含 transport、client、user、container）
     * @param data    查询条件
     * @return 分支1、2 返回包含 teams 数组的 ObjectNode；分支3 返回 null（异步回传）
     */
    public ObjectNode handleTeamList(Context context, ObjectNode data) {
        var teams = teamLifecycle.listTeam(context, data);

        // region 代理调用：其他节点查询本节点Team，直接返回结果
        if (context.isInternal) {
            var resultArray = JSONUtil.createArray();
            teams.forEach(team -> resultArray.add(teamLifecycle.serializeTeam(team)));
            return JSONUtil.create().set("teams", resultArray);
        }
        // endregion

        var allNodes = metaData.getNodes();
        var otherNodes = allNodes.stream()
                .filter(node -> !this.id.equals(node.id))
                .toList();

        // region 本节点调用 + 无其他节点：直接同步返回
        if (otherNodes.isEmpty()) {
            var resultArray = JSONUtil.createArray();
            teams.forEach(team -> resultArray.add(teamLifecycle.serializeTeam(team)));
            return JSONUtil.create().set("teams", resultArray);
        }
        // endregion

        // region 本节点调用 + 有其他节点：广播查询，异步聚合
        var requestId = context.requestId;
        var localResults = new ArrayList<ObjectNode>();
        teams.forEach(team -> localResults.add(teamLifecycle.serializeTeam(team)));

        var collector = ListQueryCollector.builder()
                .requestId(requestId)
                .originalContext(context)
                .createTime(System.currentTimeMillis())
                .build();
        collector.expectedCount = new AtomicInteger(otherNodes.size());
        collector.results = Collections.synchronizedList(new ArrayList<>(localResults));
        listQueryCollectors.put(requestId, collector);
        cleanupExpiredCollectors();

        otherNodes.forEach(node -> sendToOtherNode(EVENT_TEAM_LIST, JSONUtil.stringify(data), node.id));
        return null;
        // endregion
    }

    /**
     * 处理 team.edit 事件：编辑 Team
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据（含 teamId 及要修改的字段）
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleTeamEdit(Context context, ObjectNode data) {
        var team = teamLifecycle.editTeam(context, data);
        if (team != null) {
            return teamLifecycle.serializeTeam(team);
        }
        return null;
    }

    /**
     * 处理 team.detail 事件：获取 Team 详情。
     * Team 在本地则直接返回详情，不在本地则代理到目标节点。
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据（含 teamId）
     * @return 响应 ObjectNode，本地命中时返回；远程代理时返回 null（异步回调）
     */
    public ObjectNode handleTeamDetail(Context context, ObjectNode data) {
        var team = teamLifecycle.detailTeam(context, data);
        if (team != null) {
            return teamLifecycle.serializeTeam(team);
        }
        var teamId = data.path("teamId").asText();
        var targetNodeId = metaData.getTeamStay(teamId);
        if (targetNodeId != null && !this.id.equals(targetNodeId)) {
            var transportRecord = TransportClientRecord.builder()
                    .transportId(context.transport.getTransportId())
                    .clientHandle(context.client)
                    .build();
            teamClient.put(teamId, transportRecord);
            sendToOtherNode(EVENT_TEAM_DETAIL, JSONUtil.stringify(data), targetNodeId);
        }
        return null;
    }

    /**
     * 处理 team.remove 事件：删除 Team
     *
     * @param context 上下文（含 transport、client、user）
     * @param data    请求数据
     * @return 响应 ObjectNode，可为 null
     */
    public ObjectNode handleTeamRemove(Context context, ObjectNode data) {
        var team = teamLifecycle.removeTeam(context, data);
        if (team != null) {
            // 从缓存中移除：遍历找到并删除
            var teamId = localTeams.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(team))
                    .map(e -> e.getKey())
                    .findFirst()
                    .orElse(null);
            if (teamId != null) {
                localTeams.remove(teamId);
                metaData.removeTeamStay(teamId);
            }
            return JSONUtil.create().put("teamId", teamId);
        }
        return null;
    }

    /**
     * 处理用户事件，根据事件名分发到对应的 handle 方法。
     * 所有请求均解析 {msgId, body} 结构后进入 switch 分发；
     * 当 result != null 且为本节点请求时，通过 transport 向客户端派发同名事件。
     * 返回值用于调用方（如 listenMessage 中 proxy 模式的反向回传），
     * catch 块中的错误响应同样通过 transport 回传给客户端。
     * <p>
     * 出入事件均采用 {msgId, body} 结构，输出的 body 为 {data, error}。
     *
     * @param context  上下文（含 transport、client、user、container）
     * @param event    事件名
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串（{msgId, body} 格式），无返回值时为 null
     */
    public String handleUserEvent(Context context, String event, String dataJson) {
        try {
            if (isShuttingDown) {
                var shutdownErrorJson = buildErrorResponse("", "server is shutting down");
                context.transport.sendToClient(context.client, event, shutdownErrorJson);
                context.transport.closeClient(context.client);
                throw new RuntimeException("server is shutting down");
            }
            var msg = JSONUtil.parse(dataJson, new TypeReference<Message<ObjectNode>>() {});
            if (msg.msgId == null) {
                throw new RuntimeException("need msgId");
            }

            context.setRequestId(msg.msgId);

            if (context.transport != null) {
                context.userData = context.transport.getUser(context.client);
                context.userId = context.transport.getUserId(context.client);
            }

            this.eventInterceptor(context, event, dataJson);

            var result = switch (event) {
                case EVENT_AGENT_CREATE -> handleAgentCreate(context, msg.body);
                case EVENT_AGENT_SEND -> handleAgentSend(context, msg.body);
                case EVENT_AGENT_SUBSCRIBE -> handleAgentSubscribe(context, msg.body);
                case EVENT_AGENT_UNSUBSCRIBE -> handleAgentUnsubscribe(context, msg.body);
                case EVENT_AGENT_TOOL_CALL -> handleAgentToolCall(context, msg.body);
                case EVENT_AGENT_LIST -> handleAgentList(context, msg.body);
                case EVENT_AGENT_EDIT -> handleAgentEdit(context, msg.body);
                case EVENT_AGENT_DETAIL -> handleAgentDetail(context, msg.body);
                case EVENT_TEAM_CREATE -> handleTeamCreate(context, msg.body);
                case EVENT_TEAM_LIST -> handleTeamList(context, msg.body);
                case EVENT_TEAM_REMOVE -> handleTeamRemove(context, msg.body);
                case EVENT_TEAM_EDIT -> handleTeamEdit(context, msg.body);
                case EVENT_TEAM_DETAIL -> handleTeamDetail(context, msg.body);
                default -> null;
            };

            if (result != null && !context.isInternal) {
                context.transport.sendToClient(context.client, event, buildSuccessResponse(msg.msgId, result));
            }
            if (result != null) {
                return buildSuccessResponse(msg.msgId, result);
            }
            return null;
        } catch (Exception e) {
            var errMsg = "服务器错误";
            if (e instanceof Error) {
                errMsg = e.getMessage();
            }
            var errResponse = buildErrorResponse("", errMsg);

            if (!context.isInternal) {
                context.transport.sendToClient(context.client, event, errResponse);
            }

            return errResponse;
        }

    }

    // ======================== 集群通信 ========================

    /**
     * 概率清理过期的 listQueryCollectors（10% 概率触发，超时60秒移除）
     */
    private void cleanupExpiredCollectors() {
        if (Math.random() < 0.1) {
            var now = System.currentTimeMillis();
            listQueryCollectors.entrySet().removeIf(entry ->
                    now - entry.getValue().getCreateTime() > 60000
            );
        }
    }

    /**
     * 构建成功响应 JSON 字符串
     *
     * @param msgId 消息ID
     * @param data  响应数据
     * @return 响应 JSON 字符串（{msgId, body: {data}} 格式）
     */
    private String buildSuccessResponse(String msgId, ObjectNode data) {
        var outMsg = new Message<OutMessageBody>() {};
        outMsg.setMsgId(msgId);
        var body = new OutMessageBody() {};
        body.setData(data);
        outMsg.setBody(body);
        return JSONUtil.stringify(outMsg);
    }

    /**
     * 构建错误响应 JSON 字符串
     *
     * @param msgId 消息ID
     * @param error 错误信息
     * @return 响应 JSON 字符串（{msgId, body: {error}} 格式）
     */
    private String buildErrorResponse(String msgId, String error) {
        var outMsg = new Message<OutMessageBody>() {};
        outMsg.setMsgId(msgId);
        var body = new OutMessageBody() {};
        body.setError(error);
        outMsg.setBody(body);
        return JSONUtil.stringify(outMsg);
    }

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
     * 订阅需要本节点处理的消息，消息主题为 {nodeId}-message，
     * 消息数据结构为 JSON 数组：[sourceNodeId, type, event, body]。
     * <p>
     * proxy 模式：接收其他节点的代理请求，调用 handleUserEvent 处理并将结果反向回传。
     * reverse 模式：接收反向响应，其中 agent.list 事件走 ListQueryCollector 聚合逻辑，
     * 其他事件通过 agentClient 查找对应传输通道推送给客户端。
     */
    public void listenMessage() {
        messageBus.subscribe("%s-message".formatted(this.id), (message) -> {
            try {
                log.debug("listenMessage message: %s".formatted(message));
                var parts = JSONUtil.parseArray(message);

                var sourceNode = parts.get(0).asText();
                var type = parts.get(1).asText();
                var event = parts.get(2).asText();
                var bodyJson = parts.get(3).asText();


                switch (type) {
                    case "proxy" -> {

                        var proxyCtx = Context.builder().client(sourceNode).serverId(sourceNode).isInternal(true).build();
                        var result = handleUserEvent(proxyCtx, event, bodyJson);
                        log.debug("listenMessage proxy event: %s, result: %s".formatted(event, result));

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
                                    var remaining = collector.expectedCount.decrementAndGet();
                                    if (remaining <= 0) {
                                        listQueryCollectors.remove(ackRequestId);
                                        var mergedArray = JSONUtil.createArray();
                                        collector.results.forEach(mergedArray::add);
                                        var combined = JSONUtil.create()
                                                .put("requestId", ackRequestId)
                                                .set("data", mergedArray);
                                        var ctx = collector.originalContext;
                                        ctx.transport.sendToClient(ctx.client, event, JSONUtil.stringify(combined));
                                    }
                                }
                            }
                            // endregion
                        } else if (EVENT_TEAM_LIST.equals(event)) {
                            // region 聚合 team.list 各节点查询结果
                            var ackData = JSONUtil.parse(bodyJson);
                            var wrappedData = ackData.path("data");
                            if (!wrappedData.isMissingNode() && wrappedData.has("teams")) {
                                var ackRequestId = ackData.path("requestId").asText();
                                var collector = listQueryCollectors.get(ackRequestId);
                                if (collector != null) {
                                    var teamsArray = wrappedData.path("teams");
                                    if (teamsArray.isArray()) {
                                        teamsArray.forEach(item ->
                                                collector.results.add((ObjectNode) item));
                                    }
                                    var remaining = collector.expectedCount.decrementAndGet();
                                    if (remaining <= 0) {
                                        listQueryCollectors.remove(ackRequestId);
                                        var mergedArray = JSONUtil.createArray();
                                        collector.results.forEach(mergedArray::add);
                                        var combined = JSONUtil.create()
                                                .put("requestId", ackRequestId)
                                                .set("data", mergedArray);
                                        var ctx = collector.originalContext;
                                        ctx.transport.sendToClient(ctx.client, event, JSONUtil.stringify(combined));
                                    }
                                }
                            }
                            // endregion
                        } else if (EVENT_AGENT_DETAIL.equals(event)) {
                            // region 处理 agent.detail 反向回调：从 agentClient 查找传输通道并转发
                            var parsed = JSONUtil.parse(bodyJson);
                            var dataNode = parsed.path("body").path("data");
                            if (!dataNode.isMissingNode()) {
                                var targetId = dataNode.path("metadata").path("id").asText();
                                var record = agentClient.get(targetId);
                                if (record != null) {
                                    for (var transport : transports) {
                                        if (record.getTransportId().equals(transport.getTransportId())) {
                                            transport.sendToClient(record.getClientHandle(), event, JSONUtil.stringify(dataNode));
                                            agentClient.remove(targetId);
                                            break;
                                        }
                                    }
                                }
                            }
                            // endregion
                        } else if (EVENT_TEAM_DETAIL.equals(event)) {
                            // region 处理 team.detail 反向回调：从 teamClient 查找传输通道并转发
                            var parsed = JSONUtil.parse(bodyJson);
                            var dataNode = parsed.path("body").path("data");
                            if (!dataNode.isMissingNode()) {
                                var targetId = dataNode.path("metadata").path("id").asText();
                                var record = teamClient.get(targetId);
                                if (record != null) {
                                    for (var transport : transports) {
                                        if (record.getTransportId().equals(transport.getTransportId())) {
                                            transport.sendToClient(record.getClientHandle(), event, JSONUtil.stringify(dataNode));
                                            teamClient.remove(targetId);
                                            break;
                                        }
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