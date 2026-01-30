package me.karboom.java.iSlogger.kit.server;

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

    private SocketIOServer server;
    private Integer port;

    private Map<String, Agent> agents = new HashMap<>();
    private Map<String, Team> teams = new HashMap<>();


    /**
     *  agent缓存：  AgentId 对应 Nodes索引，-1表示不明确
     */
    private Map<String, Integer> otherAgentNode = new HashMap<>();

    /**
     * 服务器节点列表
     */
    private List<NodeInfo> nodeList = new ArrayList<>();

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    /**
     * 启动服务器后定时调用 getNodes 并且转化为nodeList，并且listen，并且定时调用broadcast
     */
    public void start() {
        var config = new Configuration();
        config.setPort(port);

        server = new SocketIOServer(config);

        // Agent 命名空间
        var agentNamespace = server.addNamespace("/agent");
        setupAgentNamespace(agentNamespace);

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
                var socketUrl = "http://" + node.ip + ":" + node.port;
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
     *  监听create事件, 随机一个节点转发事件，如果随机到自身，那么直接执行（一个空的Agent即可）
     *  监听send事件，如果自身没匹配到Agent，那么从otherAgentNode找出对应的节点并且转发，如果转发ack失败那么标记-1。如果缓存未匹配到，那么群发给其他节点, ack成功就缓存结果。
     */
    private void setupAgentNamespace(SocketIONamespace agentNamespace) {
        // 监听连接事件
        agentNamespace.addConnectListener(client -> {
            System.out.println("Client connected: " + client.getSessionId());
        });

        // 监听断开连接事件
        agentNamespace.addDisconnectListener(client -> {
            System.out.println("Client disconnected: " + client.getSessionId());
        });

        // 监听create事件
        agentNamespace.addEventListener("create", Map.class, new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackRequest) {
                var agentId = (String) data.get("agentId");
                var random = new Random();
                var targetIndex = random.nextInt(nodeList.size());
                var targetNode = nodeList.get(targetIndex);

                if (Boolean.TRUE.equals(targetNode.isLocal)) {
                    // 本节点处理，创建一个空的Agent
                    var agent = new Agent(agentId, "", null, null) {};
                    agents.put(agentId, agent);
                    ackRequest.sendAckData(Map.of("success", true, "node", "local"));
                } else if (targetNode.client != null && targetNode.client.connected()) {
                    // 转发到其他节点
                    targetNode.client.emit("create", JSONUtil.stringify(data));
                }
            }
        });

        // 监听send事件
        agentNamespace.addEventListener("send", Map.class, new DataListener<Map>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, Map data, AckRequest ackRequest) {
                var agentId = (String) data.get("agentId");
                var message = (String) data.get("message");

                var agent = agents.get(agentId);

                if (agent != null) {
                    // 本节点有该Agent，直接处理
                    agent.send(message);
                    ackRequest.sendAckData(Map.of("success", true, "node", "local"));
                } else {
                    // 本节点没有该Agent，从缓存查找
                    var nodeIndex = otherAgentNode.get(agentId);

                    if (nodeIndex != null && nodeIndex != -1) {
                        // 缓存命中，转发到指定节点
                        var targetNode = nodeList.get(nodeIndex);
                        if (targetNode.client != null && targetNode.client.connected()) {
                            targetNode.client.emit("send", new Object[]{}, args -> {
                                var success = args.length > 0 && args[0] instanceof Map && Boolean.TRUE.equals(((Map) args[0]).get("success"));
                                if (!success) {
                                    // 转发失败，标记为-1
                                    otherAgentNode.put(agentId, -1);
                                }
                            });
                        } else {
                            otherAgentNode.put(agentId, -1);
                        }
                    } else {
                        // 缓存未命中，群发给其他节点
                        for (var node : nodeList) {
                            if (Boolean.FALSE.equals(node.isLocal) && node.client != null && node.client.connected()) {
                                node.client.emit("send", new Object[]{JSONUtil.stringify(data)}, args -> {
                                    var success = args.length > 0 && args[0] instanceof Map && Boolean.TRUE.equals(((Map) args[0]).get("success"));
                                    if (success) {
                                        // 成功，缓存结果
                                        otherAgentNode.put(agentId, nodeList.indexOf(node));
                                    }
                                });
                            }
                        }
                    }
                }
            }
        });
    }

}
