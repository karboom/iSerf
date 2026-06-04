package me.karboom.java.iSerf.server.transport;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.embedded.EmbeddedHiveMQ;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 5 传输协议适配，内嵌 HiveMQ Broker 并实现 ITransport。
 * 将 MQTT 客户端消息委托给 ContainerServer 统一处理，支持双向通信。
 * <p>
 * 通信模型：
 * - 客户端向 iserf/request/{event} 发布消息（event 如 agent.create, agent.send 等）
 * - 服务端向 iserf/response/{clientId} 发布消息作为下行通道
 */
@Slf4j
public class MqttTransport implements ITransport {

    /**
     * 请求 topic 前缀
     */
    private static final String REQUEST_TOPIC_PREFIX = "iserf/request/";

    /**
     * 响应 topic 前缀
     */
    private static final String RESPONSE_TOPIC_PREFIX = "iserf/response/";

    /**
     * 内嵌 HiveMQ 实例
     */
    private EmbeddedHiveMQ hivemq;

    /**
     * MQTT 5 异步客户端（用于订阅请求 topic 和发布响应）
     */
    private Mqtt5AsyncClient mqttClient;

    /**
     * 所属的传输容器
     */
    private final ContainerServer container;

    /**
     * MQTT 监听端口
     */
    private final Integer port;

    /**
     * 传输协议唯一标识
     */
    private final String transportId;

    /**
     * 客户端会话映射：MQTT clientId -> 响应 topic
     */
    private final Map<String, String> clientTopics = new ConcurrentHashMap<>();

    /**
     * HiveMQ 数据目录临时路径
     */
    private Path dataDir;

    /**
     * 构造 MQTT 5 传输适配
     *
     * @param container 所属容器
     * @param port      MQTT 监听端口
     */
    public MqttTransport(ContainerServer container, Integer port) {
        this.container = container;
        this.port = port;
        this.transportId = DataUtil.getFlakeId();
    }

    @Override
    public String getTransportId() {
        return transportId;
    }

    /**
     * 注册客户端 topic 映射，由外部在客户端连接后调用
     *
     * @param clientId MQTT 客户端 ID
     * @param topic    对应的响应 topic
     */
    public void registerClient(String clientId, String topic) {
        clientTopics.put(clientId, topic);
        log.debug("registerClient clientId=%s topic=%s".formatted(clientId, topic));
    }

    /**
     * 注销客户端
     *
     * @param clientId MQTT 客户端 ID
     */
    public void unregisterClient(String clientId) {
        clientTopics.remove(clientId);
        log.debug("unregisterClient clientId=%s".formatted(clientId));
    }

    @Override
    public void sendToClient(Object clientHandle, String event, String bodyJson) {
        switch (clientHandle) {
            case String clientId -> {
                var topic = clientTopics.get(clientId);
                if (topic == null) {
                    log.warn("sendToClient no topic found for clientId: %s".formatted(clientId));
                    return;
                }

                try {
                    var payload = JSONUtil.stringify(JSONUtil.create()
                            .put("event", event)
                            .set("data", JSONUtil.parse(bodyJson)));
                    mqttClient.publishWith()
                            .topic(topic)
                            .qos(MqttQos.AT_LEAST_ONCE)
                            .payload(payload.getBytes(StandardCharsets.UTF_8))
                            .send();
                    log.debug("sendToClient sent event=%s to clientId=%s topic=%s".formatted(event, clientId, topic));
                } catch (Exception e) {
                    log.warn("sendToClient MQTT publish failed for clientId=%s: %s".formatted(clientId, e.getMessage()));
                }
            }
            default ->
                    log.warn("sendToClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

    @Override
    public void start() {
        try {
            // 创建临时数据目录
            dataDir = Files.createTempDirectory("hivemq-" + transportId);

            var builder = EmbeddedHiveMQ.builder()
                    .withConfigurationFolder(dataDir)
                    .withDataFolder(dataDir.resolve("data"))
                    .withExtensionsFolder(dataDir.resolve("extensions"));

            hivemq = builder.build();
            hivemq.start().get();

            // 连接内嵌 MQTT broker 作为内部客户端
            mqttClient = Mqtt5Client.builder()
                    .identifier("iserf-internal-" + transportId)
                    .serverHost("localhost")
                    .serverPort(port)
                    .buildAsync();

            mqttClient.connect()
                    .thenAccept(connAck -> {
                        log.debug("start MQTT 5 client connected: %s".formatted(connAck.getReasonCode().name()));

                        // 订阅所有请求 topic，消息到达时转发到 ContainerServer
                        mqttClient.subscribeWith()
                                .topicFilter(REQUEST_TOPIC_PREFIX + "#")
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .callback(this::handleRequest)
                                .send()
                                .thenAccept(subAck -> log.debug(
                                        "start subscribed to %s# reasonCodes=%s".formatted(REQUEST_TOPIC_PREFIX, subAck.getReasonCodes())))
                                .exceptionally(ex -> {
                                    log.error("start subscribe failed: %s".formatted(ex.getMessage()), ex);
                                    return null;
                                });
                    })
                    .exceptionally(ex -> {
                        log.error("start MQTT 5 client connect failed: %s".formatted(ex.getMessage()), ex);
                        return null;
                    })
                    .get();

            log.debug("start MqttTransport started on port %s".formatted(port));
        } catch (Exception e) {
            log.error("start MqttTransport failed to start: %s".formatted(e.getMessage()), e);
            throw new RuntimeException("Failed to start MqttTransport", e);
        }
    }

    /**
     * 处理来自 MQTT 客户端的请求消息，转发到 ContainerServer
     *
     * @param publish 收到的 MQTT 5 发布消息
     */
    private void handleRequest(Mqtt5Publish publish) {
        var topic = publish.getTopic().toString();
        var payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
        log.debug("handleRequest topic=%s payload=%s".formatted(topic, payload));

        // 从 topic 中提取事件名: iserf/request/{event}
        if (!topic.startsWith(REQUEST_TOPIC_PREFIX)) {
            log.warn("handleRequest unexpected topic: %s".formatted(topic));
            return;
        }
        var event = topic.substring(REQUEST_TOPIC_PREFIX.length());

        // 构造上下文，client 为 MQTT clientId（从 payload 中提取）
        var data = JSONUtil.parse(payload);
        var clientId = data.path("clientId").asText();
        if (clientId == null || clientId.isEmpty()) {
            log.warn("handleRequest missing clientId in payload");
            return;
        }

        var ctx = ContainerServer.Context.builder()
                .transport(this)
                .client(clientId)
                .serverId(container.id)
                .build();

        try {
            var result = container.handleUserEvent(event, ctx, payload);
            if (result != null) {
                // 将结果作为 ack 发送回客户端
                var ackPayload = JSONUtil.stringify(JSONUtil.create()
                        .put("event", "ack")
                        .put("requestEvent", event)
                        .set("data", JSONUtil.parse(result)));
                mqttClient.publishWith()
                        .topic(RESPONSE_TOPIC_PREFIX + clientId)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .payload(ackPayload.getBytes(StandardCharsets.UTF_8))
                        .send();
            }
        } catch (Exception e) {
            log.warn("handleRequest error processing event=%s: %s".formatted(event, e.getMessage()));
        }
    }

    @Override
    public void stop() {
        try {
            if (mqttClient != null) {
                mqttClient.disconnect().get();
                log.debug("stop mqttClient disconnected");
            }
            if (hivemq != null) {
                hivemq.stop().get();
                log.debug("stop hivemq stopped");
            }
            if (dataDir != null) {
                try (var stream = Files.walk(dataDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                }
                            });
                }
                log.debug("stop dataDir cleaned: %s".formatted(dataDir));
            }
        } catch (Exception e) {
            log.warn("stop MqttTransport error during stop: %s".formatted(e.getMessage()));
        }
    }
}