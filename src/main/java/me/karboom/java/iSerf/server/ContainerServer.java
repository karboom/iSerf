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

import javax.smartcardio.CardTerminal;
import java.util.*;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Context {
        public ITransport transport;
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
    public Map<String, Agent> localAgents = new HashMap<>();

    /**
     * client 缓存：AgentId 对应 TransportClientRecord（多协议互通）
     */
    public Map<String, TransportClientRecord> agentClient = new HashMap<>();

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
     * 获取所有agent
     * @param ctx
     * @param params
     * @return
     */
    protected abstract Agent listAgent(Context ctx, ObjectNode params);

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
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串
     */
    public String handleAgentCreate(Context context, String dataJson) {
        var data = JSONUtil.parse(dataJson);
        var agent = createAgent(context, data);
        var agentId = agent.id;
        localAgents.put(agentId, agent);
        metaData.setAgentStay(agentId, this.id);
        log.debug("handleAgentCreate agent.create: local " + dataJson);
        var result = JSONUtil.create().put("agentId", agentId);
        return JSONUtil.stringify(result);
    }

    /**
     * 处理 agent.send 事件：发送消息给 Agent
     *
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleAgentSend(Context context, String dataJson) {
        var data = JSONUtil.parse(dataJson);
        var agentId = data.path("agentId").asText();
        var eventObj = JSONUtil.convert(data.path("event"), Event.class);
        var agent = localAgents.get(agentId);
        if (agent != null) {
            agent.trigger(eventObj);
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_SEND, dataJson, targetNodeId);
        }
        return null;
    }

    /**
     * 处理 agent.subscribe 事件：订阅 Agent 消息流
     *
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleAgentSubscribe(Context context, String dataJson) {
        var data = JSONUtil.parse(dataJson);
        var agentId = data.path("agentId").asText();
        var agent = localAgents.get(agentId);

        if (agent != null) {
            agent.subscribe(item -> {
                var messageJson = JSONUtil.stringify(
                        JSONUtil.create()
                                .put("agentId", agentId)
                                .set("message", JSONUtil.convert(item))
                );

                    if (this.id.equals(context.serverId)) {
                        if (context.transport != null) {
                            context.transport.sendToClient(context.client, EVENT_AGENT_MESSAGE, messageJson);
                        } else {
                            log.warn("handleAgentSubscribe no transport for client: %s".formatted(context.client));
                        }
                    } else {
                        var reverseJson = "%s|%s|%s|%s".formatted(this.id, "reverse", EVENT_AGENT_MESSAGE, messageJson);
                        messageBus.publish("%s-message".formatted(context.serverId), reverseJson);
                    }
            });
            return "{\"success\":true}";
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
                    var transportRecord = TransportClientRecord.builder()
                            .transportId(context.transport.getTransportId())
                            .clientHandle(context.client)
                            .build();
                    agentClient.put(agentId, transportRecord);
            sendToOtherNode(EVENT_AGENT_SUBSCRIBE, dataJson, targetNodeId);
            return null;
        }
    }

    /**
     * 处理 agent.unsubscribe 事件：取消订阅 Agent
     *
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleAgentUnsubscribe(Context context, String dataJson) {
        var data = JSONUtil.parse(dataJson);
        var agentId = data.path("agentId").asText();
        var agent = localAgents.get(agentId);
        if (agent != null) {
            agentClient.remove(agentId);
            return "{\"success\":true}";
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_UNSUBSCRIBE, dataJson, targetNodeId);
            return null;
        }
    }

    /**
     * 处理 agent.toolCall 事件：执行工具调用
     *
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleAgentToolCall(Context context, String dataJson) {
        var data = JSONUtil.parse(dataJson);
        var agentId = data.path("agentId").asText();
        var toolCallId = data.path("toolCallId").asText();
        var agent = localAgents.get(agentId);
        log.debug("handleAgentToolCall agentId: " + agentId + ", toolCallId: " + toolCallId);
        if (agent != null) {
            var result = agent.invokeToolCallCache(toolCallId);
            return JSONUtil.stringify(result);
        } else {
            var targetNodeId = metaData.getAgentStay(agentId);
            sendToOtherNode(EVENT_AGENT_TOOL_CALL, dataJson, targetNodeId);
            return null;
        }
    }

    /**
     * 按照条件查找智能体，同时从自身和其他节点查询，然后合并
     * @param context
     * @param queryJson
     * @return
     */
    public String handleAgentList(Context context, String queryJson) {

        return "";
    }

    /**
     * 处理用户事件
     *
     * @param event    事件名
     * @param context  上下文（含 transport、client、user）
     * @param dataJson 请求数据 JSON 字符串
     * @return 响应 JSON 字符串，可为 null
     */
    public String handleUserEvent(String event, Context context, String dataJson) {
        if (isShuttingDown) {
            return SHUTTING_DOWN_ERROR;
        }
        return String.valueOf(switch (event) {
            case EVENT_AGENT_CREATE -> handleAgentCreate(context, dataJson);
            case EVENT_AGENT_SEND -> handleAgentSend(context, dataJson);
            case EVENT_AGENT_SUBSCRIBE -> handleAgentSubscribe(context, dataJson);
            case EVENT_AGENT_UNSUBSCRIBE -> handleAgentUnsubscribe(context, dataJson);
            case EVENT_AGENT_TOOL_CALL -> handleAgentToolCall(context, dataJson);
            case EVENT_AGENT_LIST -> handleAgentList(context, dataJson);
            default -> null;
        });
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
     * 消息主题为 nodeId-message，消息数据结构为|分隔（只有4段）： sourceNodeId|type|event|body
     */
    private void sendToOtherNode(String event, String data, String nodeId) {
        var body = "%s|proxy|%s|%s".formatted(this.id, event, data);
        this.messageBus.publish("%s-message".formatted(nodeId), body);
    }

    /**
     * 订阅需要本节点处理的消息
     * 消息主题为 nodeId-message，消息数据结构为 | 分隔（只有 4 段）：sourceNodeId|type|event|body
     */
    public void listenMessage() {
        messageBus.subscribe("%s-message".formatted(this.id), (message) -> {
            log.debug("listenMessage message: " + message);
            var parts = message.split("\\|", 4);

            var sourceNode = parts[0];
            var type = parts[1];
            var event = parts[2];
            var bodyJson = parts[3];

            switch (type) {
                case "proxy" -> {
                    if (isShuttingDown) {
                        var msg = "%s|%s|%s|%s".formatted(this.id, "reverse", "ack", SHUTTING_DOWN_ERROR);
                        messageBus.publish("%s-message".formatted(sourceNode), msg);
                        return;
                    }
                    var proxyCtx = Context.builder().client(sourceNode).serverId(sourceNode).build();
                    var result = handleUserEvent(event, proxyCtx, bodyJson);
                    log.debug("listenMessage proxy event: " + event + ", result: " + result);

                    if (result != null) {
                        var msg = "%s|%s|%s|%s".formatted(this.id, "reverse", "ack", result);
                        messageBus.publish("%s-message".formatted(sourceNode), msg);
                    }
                }
                case "reverse" -> {
                    var data = JSONUtil.parse(bodyJson);
                    var agentId = data.path("agentId").asText();

                    var record = agentClient.get(agentId);

                    if (record != null && !"ack".equals(event)) {
                        // 通过 transportId 找到对应 transport，委托其发送事件给客户端
                        for (var transport : transports) {
                            if (record.getTransportId().equals(transport.getTransportId())) {
                                transport.sendToClient(record.getClientHandle(), event, bodyJson);
                                break;
                            }
                        }
                    }
                }
            }
        });
    }
}