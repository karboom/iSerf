package me.karboom.java.iSerf.server.protocol;

import cn.hutool.core.util.IdUtil;
import com.corundumstudio.socketio.*;
import com.corundumstudio.socketio.listener.DataListener;
import com.corundumstudio.socketio.listener.EventInterceptor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.Node;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 去中心化 Websocket 服务器，即是服务，也是其他集群节点的代理
 */
@Slf4j
public abstract class Websocket {

    /**
     * 关闭状态下的拒绝消息
     */
    private static final String SHUTTING_DOWN_ERROR = "{\"error\":\"server is shutting down\"}";

    protected SocketIOServer server;

    public String id;
    protected String clusterIp;
    protected Integer port;

    private Map<String, Agent> localAgents = new HashMap<>();
    private Map<String, Team> teams = new HashMap<>();


    private Map<String, DataListener<String>> sysEvents = new HashMap<>();

    private Map<String, BiFunction<Object, String, String>> userEventHandler = new HashMap<>();

    abstract public Agent createAgent(ObjectNode params);

    public IMessageBus messageBus;


    // Todo 没有经过stop，异常退出了，如何解决数据
    public IMetaData metaData;

    public List<AuthorizationListener> userAuthListener = new ArrayList<>();
    public List<AuthorizationListener> sysAuthListener = new ArrayList<>();

    /**
     *  agent 缓存：AgentId 对应 Nodes 索引，-1 表示不明确
     */
    private Map<String, Integer> otherAgentNode = new HashMap<>();

    /**
     * client 缓存
     */
    private Map<String, UUID> agentClient = new HashMap<>();

    /**
     * 关闭状态标志
     */
    private volatile boolean isShuttingDown = false;


    public Websocket (String clusterIp, Integer port, IMessageBus messageBus, IMetaData metaData) {
        this.id = DataUtil.getFlakeId();
        this.clusterIp = clusterIp;
        this.port = port;
        this.messageBus = messageBus;
        this.metaData = metaData;
    }



    /**
     * 启动服务器后定时调用 getNodes 并且转化为 nodeList，并且 listen，并且定时调用 broadcast
     */
    public void start() {
        var config = new Configuration();
        config.setPort(port);
        config.setSocketConfig(new SocketConfig(){{
            setReuseAddress(true);
        }});


        // 系统授权链：优先检查关闭状态
        sysAuthListener.addFirst(data -> {
            if (isShuttingDown) {
                // 阻塞让 Nginx 等待超时，自动切换到下一个 upstream 节点
                // 超时时间应大于 Nginx 的 proxy_connect_timeout（默认 60 秒）
                try {
                    Thread.sleep(70000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }
            return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
        });

        // 添加连接授权监听，依次调用 sysAuthListener 和 userAuthListener，失败则不继续
        config.setAuthorizationListener(data -> {
            for (var listener : sysAuthListener) {
                var result = listener.getAuthorizationResult(data);
                if (result != AuthorizationResult.SUCCESSFUL_AUTHORIZATION) {
                    return AuthorizationResult.FAILED_AUTHORIZATION;
                }
            }

            for (var listener : userAuthListener) {
                var result = listener.getAuthorizationResult(data);
                if (result != AuthorizationResult.SUCCESSFUL_AUTHORIZATION) {
                    return AuthorizationResult.FAILED_AUTHORIZATION;
                }
            }

            return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
        });

        server = new SocketIOServer(config);

        // Agent 命名空间
        var agentNamespace = server.addNamespace("/user");
        setupUserNS(agentNamespace);


        // 注册JVM关闭钩子，监听SIGTERM/SIGINT信号
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.debug("start Received shutdown signal, setting isShuttingDown to true");
            isShuttingDown = true;
        }, "websocket-shutdown-hook"));

        server.start();

        this.listenMessage();

        this.metaData.addNode(Node.builder().id(this.id).port(this.port).ip(this.clusterIp).build());
    }

    public void stop() {
        isShuttingDown = true;
        metaData.removeNode(this.id);
        if (server != null) {
            server.stop();
        }
    }




    /**
     *  初始化用户侧协议
     *
     *  服务端监听客户端事件
     *  agent/create: {"prompt":"xxx"}。直接创建 Agent。响应 {"agentId":"xxx"}
     *  agent/send: {"agentId":"xxx","event":{}}。
     *  agent/active: {"agentId":"xxx"}。订阅 agent
     *  agent/leave: {"agentId":"xxx"}。取消订阅 agent
     *  agent/toolCall: {agentId, toolCallId}。
     *
     *  客户端监听服务端事件
     *  agent/message: {"agentId":"xxx","message":{}}
     *
     *
     */
    private void initUserEventHandler() {
        var nodeId = this.id;
        
        userEventHandler.put("agent/create", (client, dataJson)-> {
            var data = JSONUtil.parse(dataJson);
            var agent = createAgent(data);
            var agentId = agent.id;
            localAgents.put(agentId, agent);
            metaData.setAgentStay(agentId, nodeId);
            log.debug("initUserEventHandler agent/create: local " + dataJson);
            var result = JSONUtil.create().put("agentId", agentId);
            return JSONUtil.stringify(result);
        });

        userEventHandler.put("agent/send", (client, dataJson)-> {
            var data = JSONUtil.parse(dataJson);
            var agentId = data.path("agentId").asText();
            var eventJson = data.path("event").toString();
            var agent = localAgents.get(agentId);
            if (agent != null) {
                agent.send(eventJson);
            } else {
                var targetNodeId = metaData.getAgentStay(agentId);
                sendToOtherNode("agent/send", dataJson, targetNodeId);
            }
            return null;
        });

        userEventHandler.put("agent/active", (client, dataJson)-> {
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

                    switch (client) {
                        case SocketIOClient ioClient -> {
                            ioClient.sendEvent("agent/message", messageJson);
                        }

                        case String sourceNodeId -> {
                            var reverseJson = "%s|%s|%s|%s".formatted(this.id, "reverse", "agent/message", messageJson);

                            messageBus.publish("%s-message".formatted(sourceNodeId), reverseJson);
                        }
                        default -> throw new IllegalStateException("Unexpected value: " + client);
                    }

                });
                return "{\"success\":true}";
            } else {
                var targetNodeId = metaData.getAgentStay(agentId);
                agentClient.put(agentId, ((SocketIOClient) client).getSessionId());
                sendToOtherNode("agent/active", dataJson, targetNodeId);
                return null;
            }
        });

        userEventHandler.put("agent/leave", (client, dataJson)-> {
            var data = JSONUtil.parse(dataJson);
            var agentId = data.path("agentId").asText();
            var agent = localAgents.get(agentId);
            if (agent != null) {
                agentClient.remove(agentId);
                return "{\"success\":true}";
            } else {
                var targetNodeId = metaData.getAgentStay(agentId);
                sendToOtherNode("agent/leave", dataJson, targetNodeId);
                return null;
            }
        });

        userEventHandler.put("agent/toolCall", (client, dataJson)-> {
            var data = JSONUtil.parse(dataJson);
            var agentId = data.path("agentId").asText();
            var toolCallId = data.path("toolCallId").asText();
            var agent = localAgents.get(agentId);
            log.debug("initUserEventHandler agent/toolCall agentId: " + agentId + ", toolCallId: " + toolCallId);
            if (agent != null) {
                var result = agent.invokeToolCallCache(toolCallId);
                return JSONUtil.stringify(result);
            } else {
                var targetNodeId = metaData.getAgentStay(agentId);
                sendToOtherNode("agent/toolCall", dataJson, targetNodeId);
                return null;
            }
        });
    }

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
        messageBus.subscribe("%s-message".formatted(this.id), (message)-> {
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
                    var handler = userEventHandler.get(event);
                    if (handler != null) {
                        var result = handler.apply(sourceNode, bodyJson);
                        log.debug("listenMessage proxy event: " + event + ", result: " + result);

                        if (result != null) {
                            var msg = "%s|%s|%s|%s".formatted(this.id, "reverse", "ack", result);
                            messageBus.publish("%s-message".formatted(sourceNode), msg);
                        }
                    }
                }
                case "reverse" -> {
                    var data = JSONUtil.parse(bodyJson);
                    var agentId = data.path("agentId").asText();

                    var clientId = agentClient.get(agentId);

                    if (!event.equals("ack")) {
                        var client = server.getNamespace("/user").getClient(clientId);
                        client.sendEvent(event, bodyJson);
                    }

                }
            }
        });
    }
    /**
     * 初始化服务间通信
     *
     * 监听事件
     * proxy: {"event":"xxx","body":"{}"}。根据 event 直接调用 userEvent 里面的处理函数
     * reverse: {event,"body":"{}"}。根据 agentId 从 agentClient 获取客户端，然后发送 body
     *
     */
    private void initSysEvents() {

        sysEvents.put("reverse", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);

                var body = data.get("body");
                var agentId = body.get("agentId").asString();
                var bodyJson = data.path("body").toString();
                var sessionId = agentClient.get(agentId);

                if (sessionId != null) {
                    var targetClient = server.getNamespace("/user").getClient(sessionId);
                    if (targetClient != null) {
                        targetClient.sendEvent("agent/message", bodyJson);
//                        ackSender.sendAckData("{\"success\":true}");
                    } else {
//                        ackSender.sendAckData("{\"success\":false,\"reason\":\"client not found\"}");
                    }
                } else {
//                    ackSender.sendAckData("{\"success\":false,\"reason\":\"agent not subscribed\"}");
                }
            }
        });
    }


    protected void setupUserNS(SocketIONamespace userNS) {
        initUserEventHandler();
        
        // 添加事件拦截器，关闭状态下统一拒绝
        userNS.addEventInterceptor(new EventInterceptor() {
            @Override
            public void onEvent(com.corundumstudio.socketio.transport.NamespaceClient client, String eventName, java.util.List<Object> args, AckRequest ackRequest) {
                if (isShuttingDown) {
                    ackRequest.sendAckData(SHUTTING_DOWN_ERROR);
                }
            }
        });
        
        userEventHandler.forEach((event, handler) -> {
            userNS.addEventListener(event, String.class, (client, dataJson, ackSender) -> {
                if (isShuttingDown) {
                    ackSender.sendAckData(SHUTTING_DOWN_ERROR);
                    return;
                }
                var result = handler.apply(client, dataJson);
                if (result != null) {
                    ackSender.sendAckData(result);
                }
            });
        });
    }

}