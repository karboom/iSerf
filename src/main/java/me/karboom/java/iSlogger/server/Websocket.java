package me.karboom.java.iSlogger.server;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.SocketIONamespace;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DisconnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSlogger.agent.Agent;
import me.karboom.java.iSlogger.team.Team;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.util.JSONUtil;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 去中心化 Websocket 服务器，即是服务，也是其他集群节点的代理
 */
@Slf4j
public abstract class Websocket {
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static public class NodeInfo {
        public String ip;
        public Integer port;
        /**
         * 是否本节点
         */
        public Boolean isLocal;

        public Socket client;
    }

    protected SocketIOServer server;
    protected Integer port;

    private Map<String, Agent> agents = new HashMap<>();
    private Map<String, Team> teams = new HashMap<>();


    private Map<String, DataListener> userEvents = new HashMap<>();
    private Map<String, DataListener> sysEvents = new HashMap<>();


    /**
     *  agent缓存：  AgentId 对应 Nodes索引，-1表示不明确
     */
    private Map<String, Integer> otherAgentNode = new HashMap<>();

    /**
     * client缓存
     */
    private Map<String, String> agentClient = new HashMap<>();

    /**
     * 服务器节点列表
     */
    private List<NodeInfo> nodeList = new ArrayList<>();

    protected ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    /**
     * 启动服务器后定时调用 getNodes 并且转化为nodeList，并且listen，并且定时调用broadcast
     */
    public void start() {
        var config = new Configuration();
        config.setPort(port);

        server = new SocketIOServer(config);

        // Agent 命名空间
        var agentNamespace = server.addNamespace("/user");
        setupUserNS(agentNamespace);
        var sysNamespace = server.addNamespace("/sys");
        setupSysNS(sysNamespace);


        server.start();

        // 定时调用 getNodes 更新节点列表
        scheduler.scheduleAtFixedRate(() -> {
            var nodeAddresses = getNodes();
            updateNodeList(nodeAddresses);
            listen();
        }, 0, 5, TimeUnit.SECONDS);

        // 定时广播
        scheduler.scheduleAtFixedRate(this::broadcast, 1, 5, TimeUnit.SECONDS);
    }

    /**
     * 更新节点列表
     */
    private void updateNodeList(List<String> nodeAddresses) {
        var localIp = "127.0.0.1";
        var localPort = 9092;
        var currentNodeMap = new HashMap<String, NodeInfo>();

        for (var address : nodeAddresses) {
            var parts = address.split(":");
            var ip = parts[0];
            var port = Integer.parseInt(parts[1]);
            var isLocal = ip.equals(localIp) && port == localPort;
            var existingNode = nodeList.stream()
                    .filter(n -> n.ip.equals(ip) && n.port.equals(port))
                    .findFirst()
                    .orElse(null);

            var nodeInfo = existingNode != null ? existingNode : NodeInfo.builder()
                    .ip(ip)
                    .port(port)
                    .isLocal(isLocal)
                    .client(null)
                    .build();

            currentNodeMap.put(address, nodeInfo);
        }

        nodeList = new ArrayList<>(currentNodeMap.values());
    }

    /**
     * 向其他节点广播
     * 事件：alive
     */
    public void broadcast() {
        for (var node : nodeList) {
            if (Boolean.FALSE.equals(node.isLocal) && node.client != null && node.client.connected()) {
                node.client.emit("alive", Map.of("ip", "127.0.0.1", "port", 9092));
            }
        }
    }

    /**
     * 建立和其他节点的 websocket 连接
     * 监听：alive
     */
    @SneakyThrows
    public void listen() {
        for (var node : nodeList) {
            if (Boolean.FALSE.equals(node.isLocal) && (node.client == null || !node.client.connected())) {
                var socketUrl = "http://" + node.ip + ":" + node.port + "/sys";
                var options = new IO.Options();
                options.transports = new String[]{"websocket"};

                var socket = IO.socket(socketUrl, options);
                
                socket.on(Socket.EVENT_CONNECT, args -> {
                    System.out.println("Connected to node: " + node.ip + ":" + node.port);
                });
                
                socket.on("alive", args -> {
                    if (args.length > 0 && args[0] instanceof Map) {
                        var ip = (String) ((Map) args[0]).get("ip");
                        var port = ((Map) args[0]).get("port");
                        System.out.println("Received alive from: " + ip + ":" + port);
                    }
                });
                
                socket.connect();
                node.setClient(socket);
            }
        }
    }

    /**
     *
     * @return  服务器节点地址列表
     */
    abstract public List<String> getNodes();

    /**
     * 代理转发到其他节点
     * @param agentId Agent ID
     * @param event 事件名称
     * @param data 数据
     * @param ackSender Ack回调
     */
    @SneakyThrows
    private void proxyToOtherNode(String agentId, String event, Map data, AckRequest ackSender) {
        var nodeIndex = otherAgentNode.get(agentId);

        if (nodeIndex != null && nodeIndex != -1) {
            var targetNode = nodeList.get(nodeIndex);
            if (targetNode.client != null && targetNode.client.connected()) {
                targetNode.client.emit(event, new Map[]{data}, args -> {
                    var success = args.length > 0 && args[0] instanceof Map && Boolean.TRUE.equals(((Map) args[0]).get("success"));
                    if (success) {
                        ackSender.sendAckData(args[0]);
                    } else {
                        otherAgentNode.put(agentId, -1);
                        ackSender.sendAckData(Map.of("success", false));
                    }
                });
            } else {
                otherAgentNode.put(agentId, -1);
                ackSender.sendAckData(Map.of("success", false));
            }
        } else {
            for (var node : nodeList) {
                if (Boolean.FALSE.equals(node.isLocal) && node.client != null && node.client.connected()) {
                    node.client.emit(event, new Map[]{data}, args -> {
                        var success = args.length > 0 && args[0] instanceof Map && Boolean.TRUE.equals(((Map) args[0]).get("success"));
                        if (success) {
                            otherAgentNode.put(agentId, nodeList.indexOf(node));
                            ackSender.sendAckData(args[0]);
                        }
                    });
                }
            }
        }
    }

    /**
     *  初始化用户侧协议
     *
     *  服务端监听客户端事件
     *  agent/create: {prompt}。 随机一个节点转发事件，如果随机到自身，那么直接执行。响应 {agentId}
     *  agent/send: {agentId, event: {}}。
     *  agent/active: {agentId}。订阅agent
     *  agent/leave: {agentId}。 取消订阅agent
     *
     *  agent/send、agent/active、agent/leave 实现自动节点转发，如果本节点未找到Agent，那么从otherAgentNode找出对应的节点并且转发，如果转发ack失败那么标记-1。如果缓存未匹配到，那么群发给其他节点, ack成功就缓存结果
     *
     *  客户端监听服务端事件
     *  message: {agentId, item}
     *
     *
     */
    private void initUserEvents () {
        userEvents.put("agent/create", new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var prompt = (String) data.get("prompt");
                var random = new Random();
                var targetIndex = random.nextInt(nodeList.size());
                var targetNode = nodeList.get(targetIndex);

                if (Boolean.TRUE.equals(targetNode.isLocal)) {
                    var agentId = UUID.randomUUID().toString();
                    var agent = new Agent(agentId, prompt, null, null) {};
                    agents.put(agentId, agent);
                    ackSender.sendAckData(Map.of("agentId", agentId));
                } else if (targetNode.client != null && targetNode.client.connected()) {
                    targetNode.client.emit("agent/create", new Object[]{data}, args -> {
                        if (args.length > 0 && args[0] instanceof Map) {
                            ackSender.sendAckData(args[0]);
                        }
                    });
                }
            }
        });

        userEvents.put("agent/send", new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var agentId = (String) data.get("agentId");
                var event = (Map) data.get("event");
                var agent = agents.get(agentId);

                if (agent != null) {
                    agent.send(JSONUtil.stringify(event));
                } else {
                    proxyToOtherNode(agentId, "agent/send", data, ackSender);
                }
            }
        });

        userEvents.put("agent/active", new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var agentId = (String) data.get("agentId");
                var agent = agents.get(agentId);

                if (agent != null) {
                    agentClient.put(agentId, client.getSessionId().toString());
                    agent.subscribe(item -> client.sendEvent("message", Map.of("agentId", agentId, "item", item)));
                    ackSender.sendAckData(Map.of("success", true));
                } else {
                    proxyToOtherNode(agentId, "agent/active", data, ackSender);
                }
            }
        });

        userEvents.put("agent/leave", new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var agentId = (String) data.get("agentId");
                var agent = agents.get(agentId);

                if (agent != null) {
                    agentClient.remove(agentId);
                    ackSender.sendAckData(Map.of("success", true));
                } else {
                    proxyToOtherNode(agentId, "agent/leave", data, ackSender);
                }
            }
        });
    }

    /**
     * 初始化服务间通信
     *
     * 监听事件
     * alive: {timestamp} 。
     * proxy: {event, body} 。根据event直接调用userEvent里面的处理函数
     *
     */
    private void initSysEvents() {
        sysEvents.put("alive", new DataListener<Map>() {
            @Override
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var timestamp = data.get("timestamp");
                log.debug("initSysEvents received alive with timestamp: {}", timestamp);
            }
        });

        sysEvents.put("proxy", new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackSender) {
                var event = (String) data.get("event");
                var body = (Map) data.get("body");
                var listener = userEvents.get(event);

                if (listener != null) {
                    listener.onData(client, body, ackSender);
                } else {
                    ackSender.sendAckData(Map.of("success", false));
                }
            }
        });
    }


    protected void setupUserNS(SocketIONamespace userNS) {
        initUserEvents();
        userEvents.forEach((event, listener) -> userNS.addEventListener(event, Map.class, listener));
    }


    protected void setupSysNS(SocketIONamespace sysNS) {
        initSysEvents();
        sysEvents.forEach((event, listener) -> sysNS.addEventListener(event, Map.class, listener));
    }
}
