package me.karboom.java.iSlogger.server;

import com.corundumstudio.socketio.*;
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
import me.karboom.java.iSlogger.llm.text.OpenAI;
import me.karboom.java.iSlogger.team.Team;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.util.JSONUtil;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    protected String clusterIp;
    protected Integer port;

    private Map<String, Agent> agents = new HashMap<>();
    private Map<String, Team> teams = new HashMap<>();


    private Map<String, DataListener<String>> userEvents = new HashMap<>();
    private Map<String, DataListener<String>> sysEvents = new HashMap<>();


    /**
     *  agent 缓存：AgentId 对应 Nodes 索引，-1 表示不明确
     */
    private Map<String, Integer> otherAgentNode = new HashMap<>();

    /**
     * client 缓存
     */
    private Map<String, UUID> agentClient = new HashMap<>();

    /**
     * 服务器节点列表
     */
    private List<NodeInfo> nodeList = new ArrayList<>();

    protected ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Websocket (String clusterIp, Integer port) {
        this.clusterIp = clusterIp;
        this.port = port;
    }

    /**
     *  Todo 根据网络接口自动解析clusterIp 和 port
     */
    public Websocket () {
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
            parseNodes(nodeAddresses);
            listen();
        }, 0, 5, TimeUnit.SECONDS);

        // 定时广播
        scheduler.scheduleAtFixedRate(this::broadcast, 1, 5, TimeUnit.SECONDS);
    }

    /**
     *
     * @return  服务器节点地址列表
     */
    abstract public List<String> getNodes();

    /**
     * 解析节点列表

     * x 遍历 nodeList，根据解析情况进行新增和删除
     *
     * Todo 如果数量减少了，其中的 agents 如何处理
     *
     * @param nodeAddresses ip:port 字符串
     */
    @SneakyThrows
    private void parseNodes(List<String> nodeAddresses) {
        var newNodeList = new ArrayList<NodeInfo>();


        for (var addr : nodeAddresses) {
            var parts = addr.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid node address format: " + addr);
            }
            var ip = parts[0];
            var port = Integer.parseInt(parts[1]);

            newNodeList.add(new NodeInfo(ip, port, Objects.equals(ip, clusterIp) && this.port == port, null));
        }


        // 更新节点列表，保留已有连接的节点
        var updatedNodeList = new ArrayList<NodeInfo>();
        for (var newNode : newNodeList) {
            var existingNode = nodeList.stream()
                    .filter(n -> n.ip.equals(newNode.ip) && n.port.equals(newNode.port))
                    .findFirst()
                    .orElse(null);
            
            if (existingNode != null) {
                updatedNodeList.add(existingNode);
            } else {
                updatedNodeList.add(newNode);
            }
        }


        this.nodeList = updatedNodeList;
    }

    /**
     * 向其他节点广播
     * 事件：alive
     */
    public void broadcast() {
        for (var node : nodeList) {
            if (Boolean.FALSE.equals(node.isLocal) && node.client != null && node.client.connected()) {
                var dataJson = "{\"ip\":\"127.0.0.1\",\"port\":9092}";
                node.client.emit("alive", dataJson);
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
                


                socket.connect();
                node.setClient(socket);
            }
        }
    }


    /**
     * 代理转发到其他节点
     * - 解析事件名称 ns/name，根据不同的 ns 和 name 区分节点路由方式
     * -- agent/create，根据targetNode发送
     * -- agent/其他，需要先匹配 otherAgentNode，如果没有命中，给其他非自身 node 群发，然后根据响应更新缓存
     * - 转发的 data 需要新增 source: ip:port 字段，并且封装为 {event, body} 格式发送
     * - emit 方法需要阻塞线程
     */
    @SneakyThrows
    private void proxyToOtherNode(String event, ObjectNode data, AckRequest ackSender, NodeInfo specialNode) {
        var parts = event.split("/");
        var ns = parts[0];
        var name = parts.length > 1 ? parts[1] : "";
        var source = clusterIp + ":" + port;

        log.debug("proxyToOtherNode: event={}, ns={}, name={}, source={}", event, ns, name, source);

        data.put("source", source);

        switch (ns) {
            case "agent" -> {
                switch (name) {
                    case "create" -> {
                        var targetNode = specialNode;

                        var proxyData = JSONUtil.create();
                        proxyData.put("event", event);
                        proxyData.set("body", data);
                        var proxyDataJson = JSONUtil.stringify(proxyData);
                        var latch = new CountDownLatch(1);
                        var responseRef = new AtomicReference<String>();
                        targetNode.client.emit("proxy", new String[]{proxyDataJson}, args -> {
                            if (args.length > 0 && args[0] instanceof String) {
                                responseRef.set(args[0].toString());
                            }
                            latch.countDown();
                        });
                        latch.await(5, TimeUnit.SECONDS);
                        var response = responseRef.get();
                        if (response != null) {
                            ackSender.sendAckData(response);
                        } else {
                            ackSender.sendAckData("{\"success\":false}");
                        }
                    }
                    default -> {
                        var agentId = data.path("agentId").asText();
                        var nodeIndex = otherAgentNode.get(agentId);
                        var proxyData = JSONUtil.create();
                        proxyData.put("event", event);
                        proxyData.set("body", data);
                        var proxyDataJson = JSONUtil.stringify(proxyData);

                        if (nodeIndex != null && nodeIndex != -1) {
                            var targetNode = nodeList.get(nodeIndex);
                            if (targetNode != null && targetNode.client != null && targetNode.client.connected()) {
                                var latch = new CountDownLatch(1);
                                var responseRef = new AtomicReference<String>();
                                targetNode.client.emit("proxy", new String[]{proxyDataJson}, args -> {
                                    if (args.length > 0 && args[0] instanceof String) {
                                        responseRef.set(args[0].toString());
                                    } else {
                                        otherAgentNode.put(agentId, -1);
                                    }
                                    latch.countDown();
                                });
                                latch.await(5, TimeUnit.SECONDS);
                                var response = responseRef.get();
                                if (response != null) {
                                    ackSender.sendAckData(response);
                                } else {
                                    ackSender.sendAckData("{\"success\":false}");
                                }
                            } else {
                                otherAgentNode.put(agentId, -1);
                                ackSender.sendAckData("{\"success\":false}");
                            }
                        } else {
                            var responseRef = new AtomicReference<String>();
                            var latch = new CountDownLatch(1);
                            var hasResponded = new AtomicReference<>(false);
                            for (var node : nodeList) {
                                if (Boolean.FALSE.equals(node.isLocal) && node.client != null && node.client.connected()) {
                                    node.client.emit("proxy", new String[]{proxyDataJson}, args -> {
                                        if (args.length > 0 && args[0] instanceof String && !hasResponded.get()) {
                                            if (hasResponded.compareAndSet(false, true)) {
                                                responseRef.set(args[0].toString());
                                                otherAgentNode.put(agentId, nodeList.indexOf(node));
                                            }
                                        }
                                        latch.countDown();
                                    });
                                }
                            }
                            latch.await(5, TimeUnit.SECONDS);
                            var response = responseRef.get();
                            if (response != null) {
                                ackSender.sendAckData(response);
                            } else {
                                ackSender.sendAckData("{\"success\":false}");
                            }
                        }
                    }
                }
            }
            default -> ackSender.sendAckData("{\"success\":false,\"reason\":\"unknown namespace\"}");
        }
    }

    /**
     *  初始化用户侧协议
     *
     *  服务端监听客户端事件
     *  agent/create: {"prompt":"xxx"}。如果存在source字段，直接处理。否则随机一个节点转发事件，如果随机到自身，那么直接执行。响应 {"agentId":"xxx"}
     *  agent/send: {"agentId":"xxx","event":{}}。
     *  agent/active: {"agentId":"xxx"}。订阅 agent
     *  agent/leave: {"agentId":"xxx"}。取消订阅 agent
     *
     *  客户端监听服务端事件
     *  message: {"agentId":"xxx","item":{}}
     *
     *
     */
    private void initUserEvents () {
        userEvents.put("agent/create", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);
                var prompt = data.path("prompt").asText();



                var random = new Random();
                var targetIndex = random.nextInt(nodeList.size());
                var targetNode = nodeList.get(targetIndex);

                // 临时测试
                targetNode = nodeList.stream().filter(o -> !o.getIsLocal()).findFirst().orElse(null);


                if (!data.path("source").isMissingNode() || Boolean.TRUE.equals(targetNode.isLocal)) {
                    var agentId = UUID.randomUUID().toString();
                    var agent = new Agent(agentId, prompt, new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"), "https://dashscope.aliyuncs.com/compatible-mode/v1", 3), null) {};
                    agents.put(agentId, agent);
                    System.out.println("<agent/create> local %s".formatted(dataJson));
                    ackSender.sendAckData("{\"agentId\":\"%s\"}".formatted(agentId));
                } else if (targetNode.client != null && targetNode.client.connected()) {
                    System.out.println("<agent/create> %s cluster %s".formatted(port, dataJson));

                    proxyToOtherNode("agent/create", data, ackSender, targetNode);

                }
            }
        });

        userEvents.put("agent/send", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);
                var agentId = data.path("agentId").asText();
                var eventJson = data.path("event").toString();
                var agent = agents.get(agentId);

                if (agent != null) {
                    agent.send(eventJson);
                } else {
                    proxyToOtherNode("agent/send", data, ackSender, null);
                }
            }
        });

        userEvents.put("agent/active", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);
                var agentId = data.path("agentId").asText();
                var agent = agents.get(agentId);

                agentClient.put(agentId, client.getSessionId());

                if (agent != null) {
                    agent.subscribe(item -> {
                        var messageJson = "{\"agentId\":\"%s\",\"item\":%s}".formatted(agentId, JSONUtil.stringify(item));

                        if (!data.path("source").isMissingNode()) {
                            var reverseJson = "{\"event\":\"%s\",\"body\":%s}".formatted("message", messageJson);
                            var targetNode = nodeList.stream().filter(o -> {
                                return "%s:%s".formatted(o.ip, o.port).equals(data.get("source").asString());
                            }).toList();
                            targetNode.getFirst().client.emit("reverse", reverseJson);
                        } else {
                            client.sendEvent("message", messageJson);
                        }
                    });
                    ackSender.sendAckData("{\"success\":true}");
                } else {
                    proxyToOtherNode("agent/active", data, ackSender, null);
                }
            }
        });

        userEvents.put("agent/leave", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);
                var agentId = data.path("agentId").asText();
                var agent = agents.get(agentId);

                if (agent != null) {
                    agentClient.remove(agentId);
                    ackSender.sendAckData("{\"success\":true}");
                } else {
                    proxyToOtherNode("agent/leave", data, ackSender, null);
                }
            }
        });
    }

    /**
     * 初始化服务间通信
     *
     * 监听事件
     * alive: {"ip", port, timestamp}。 根据其他节点广播的存活消息，更新节点状态
     * proxy: {"event":"xxx","body":"{}"}。根据 event 直接调用 userEvent 里面的处理函数
     * reverse: {event,"body":"{}"}。根据 agentId 从 agentClient 获取客户端，然后发送 body
     *
     */
    private void initSysEvents() {
        sysEvents.put("alive", new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
            }
        });

        sysEvents.put("proxy", new DataListener<String>() {
            @Override
            @SneakyThrows
            public void onData(SocketIOClient client, String dataJson, AckRequest ackSender) {
                var data = JSONUtil.parse(dataJson);
                var event = data.path("event").asText();
                var bodyJson = data.path("body").toString();
                var listener = userEvents.get(event);


                listener.onData(client, bodyJson, ackSender);
            }
        });

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
                        targetClient.sendEvent("message", bodyJson);
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
        initUserEvents();
        userEvents.forEach((event, listener) -> userNS.addEventListener(event, String.class, listener));
    }


    protected void setupSysNS(SocketIONamespace sysNS) {
        initSysEvents();
        sysEvents.forEach((event, listener) -> sysNS.addEventListener(event, String.class, listener));
    }
}