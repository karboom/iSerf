package me.karboom.java.iSerf.server.transport;

import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉 Stream 模式传输适配，通过 OkHttp WebSocket 连接钉钉开放平台。
 * 将钉钉消息委托给 ContainerServer 统一处理，支持双向通信。
 * <p>
 * 通信模型：
 * - 钉钉 Stream 模式 WebSocket 连接 wss://wss.dingtalk.com
 * - 上行：钉钉推送消息 -> 转发到 ContainerServer.handleUserEvent
 * - 下行：ContainerServer 调用 sendToClient -> 通过 WebSocket 回复钉钉消息
 */
@Slf4j
public abstract class DingtalkTransport implements ITransport {

    /**
     * 钉钉 Stream 模式 WebSocket 地址
     */
    private static final String DINGTALK_WS_URL = "wss://wss.dingtalk.com";

    /**
     * 钉钉 API 基础地址
     */
    private static final String DINGTALK_API_BASE = "https://api.dingtalk.com";

    /**
     * 所属的传输容器
     */
    private final ContainerServer container;

    /**
     * 钉钉应用 AppKey
     */
    private final String appKey;

    /**
     * 钉钉应用 AppSecret
     */
    private final String appSecret;

    /**
     * 传输协议唯一标识
     */
    private final String transportId;

    /**
     * HTTP 客户端
     */
    private final OkHttpClient httpClient;

    /**
     * WebSocket 连接
     */
    private WebSocket webSocket;

    /**
     * 钉钉 accessToken
     */
    private String accessToken;

    /**
     * WebSocket 连接是否已就绪
     */
    private volatile boolean ready = false;

    /**
     * 客户端映射：钉钉 senderStaffId -> 钉钉 conversationId（用于下行回复）
     */
    private final Map<String, String> clientConversations = new ConcurrentHashMap<>();

    /**
     * 构造钉钉 Stream 模式传输适配
     *
     * @param container 所属容器
     * @param appKey    钉钉应用 AppKey
     * @param appSecret 钉钉应用 AppSecret
     */
    public DingtalkTransport(ContainerServer container, String appKey, String appSecret) {
        this.container = container;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.transportId = DataUtil.getFlakeId();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getTransportId() {
        return transportId;
    }

    @Override
    public Object getUser(Object clientHandle) {
        return clientHandle;
    }

    @Override
    public void closeClient(Object clientHandle) {
        switch (clientHandle) {
            case String senderStaffId -> clientConversations.remove(senderStaffId);
            default -> log.warn("closeClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

    @Override
    public abstract void authorizeConnection(Object clientHandle);

    @Override
    public abstract String getUserId(Object clientHandle);

    @Override
    public void sendToClient(Object clientHandle, String event, String bodyJson) {
        switch (clientHandle) {
            case String senderStaffId -> {
                if (webSocket == null || !ready) {
                    log.warn("sendToClient WebSocket not ready, dropping message for senderStaffId=%s".formatted(senderStaffId));
                    return;
                }

                var conversationId = clientConversations.get(senderStaffId);
                if (conversationId == null) {
                    log.warn("sendToClient no conversationId found for senderStaffId=%s".formatted(senderStaffId));
                    return;
                }

                try {
                    var content = JSONUtil.parse(bodyJson).path("content").asText("");

                    var replyPayload = JSONUtil.stringify(JSONUtil.create()
                            .put("msgtype", "text")
                            .set("text", JSONUtil.create()
                                    .put("content", content)));

                    // 构造钉钉下行消息帧
                    var df = JSONUtil.create()
                            .set("headers", JSONUtil.create()
                                    .put("topic", "response")
                                    .put("messageId", DataUtil.getFlakeId()))
                            .set("data", JSONUtil.create()
                                    .put("conversationId", conversationId)
                                    .set("msgBody", JSONUtil.parse(replyPayload)));

                    var success = webSocket.send(JSONUtil.stringify(df));
                    if (success) {
                        log.debug("sendToClient sent to senderStaffId=%s conversationId=%s".formatted(senderStaffId, conversationId));
                    } else {
                        log.warn("sendToClient WebSocket send failed for senderStaffId=%s".formatted(senderStaffId));
                    }
                } catch (Exception e) {
                    log.warn("sendToClient error for senderStaffId=%s: %s".formatted(senderStaffId, e.getMessage()));
                }
            }
            default ->
                    log.warn("sendToClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

    @Override
    public void start() {
        try {
            // 1. 获取钉钉 accessToken
            fetchAccessToken();

            // 2. 建立 WebSocket 连接
            connectWebSocket();

            log.debug("start DingtalkTransport started, transportId=%s".formatted(transportId));
        } catch (Exception e) {
            log.error("start DingtalkTransport failed: %s".formatted(e.getMessage()), e);
            throw new RuntimeException("Failed to start DingtalkTransport", e);
        }
    }

    /**
     * 获取钉钉 accessToken
     */
    private void fetchAccessToken() throws Exception {
        var url = DINGTALK_API_BASE + "/v1.0/oauth2/accessToken";

        var body = JSONUtil.stringify(JSONUtil.create()
                .put("appKey", appKey)
                .put("appSecret", appSecret));

        var request = new Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "no body";
                log.error("fetchAccessToken HTTP %s: %s".formatted(response.code(), errorBody));
                throw new RuntimeException("Failed to get accessToken: HTTP " + response.code());
            }

            var responseBody = response.body() != null ? response.body().string() : "";
            var json = JSONUtil.parse(responseBody);
            accessToken = json.path("accessToken").asText();

            if (accessToken == null || accessToken.isEmpty()) {
                throw new RuntimeException("accessToken is empty in response: " + responseBody);
            }
            log.debug("fetchAccessToken success");
        }
    }

    /**
     * 建立钉钉 Stream 模式 WebSocket 连接
     */
    private void connectWebSocket() {
        var wsUrl = DINGTALK_WS_URL + "?access_token=" + accessToken;

        var request = new Request.Builder()
                .url(wsUrl)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                log.debug("connectWebSocket onOpen");

                // 注册回调 TOPIC
                var registerPayload = JSONUtil.stringify(JSONUtil.create()
                        .set("headers", JSONUtil.create()
                                .put("topic", "REGISTER")
                                .put("messageId", DataUtil.getFlakeId()))
                        .set("data", JSONUtil.create()
                                .put("appKey", appKey)
                                .set("subscriptions", JSONUtil.createArray()
                                        .add(JSONUtil.create()
                                                .put("type", "CALLBACK")
                                                .put("topic", "/v1.0/im/bot/messages/get")
                                                .put("topic", "/v1.0/im/bot/messages/send")))));

                var success = webSocket.send(registerPayload);
                if (success) {
                    ready = true;
                    log.debug("connectWebSocket REGISTER sent successfully");
                } else {
                    log.warn("connectWebSocket REGISTER send failed");
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                log.debug("onMessage: %s".formatted(text));
                handleDingtalkMessage(text);
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                log.debug("onClosing code=%s reason=%s".formatted(code, reason));
                ready = false;
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                log.debug("onClosed code=%s reason=%s".formatted(code, reason));
                ready = false;
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                log.error("onFailure: %s".formatted(t.getMessage()), t);
                if (response != null && response.body() != null) {
                    try {
                        log.error("onFailure response body: %s".formatted(response.body().string()));
                    } catch (Exception ignored) {
                    }
                }
                ready = false;
            }
        });

        log.debug("connectWebSocket connecting...");
    }

    /**
     * 处理钉钉推送的消息帧，转发到 ContainerServer
     *
     * @param text WebSocket 文本消息
     */
    private void handleDingtalkMessage(String text) {
        try {
            var frame = JSONUtil.parse(text);
            var headers = frame.path("headers");
            var topic = headers.path("topic").asText();

            if ("response".equals(topic) || "REGISTER".equals(topic)) {
                log.debug("handleDingtalkMessage ignoring system topic: %s".formatted(topic));
                return;
            }

            var data = frame.path("data");
            var messageId = headers.path("messageId").asText();

            // 提取钉钉消息内容
            var senderStaffId = data.path("senderStaffId").asText();
            var conversationId = data.path("conversationId").asText();

            if (senderStaffId != null && conversationId != null) {
                clientConversations.put(senderStaffId, conversationId);
            }

            // 将钉钉消息作为 agent.send 事件转发到 ContainerServer
            var ctx = ContainerServer.Context.builder()
                    .transport(this)
                    .client(senderStaffId)
                    .serverId(container.id)
                    .server(container)
                    .isInternal(false)
                    .build();

            var eventData = JSONUtil.stringify(JSONUtil.create()
                    .put("senderStaffId", senderStaffId)
                    .put("conversationId", conversationId)
                    .put("messageId", messageId)
                    .set("rawData", data));

            container.handleUserEvent(ctx, ContainerServer.EVENT_AGENT_SEND, eventData);
        } catch (Exception e) {
            log.warn("handleDingtalkMessage error: %s".formatted(e.getMessage()));
        }
    }

    @Override
    public void stop() {
        ready = false;
        if (webSocket != null) {
            try {
                webSocket.close(1000, "transport stopping");
                log.debug("stop WebSocket closed");
            } catch (Exception e) {
                log.warn("stop WebSocket close error: %s".formatted(e.getMessage()));
            }
        }
        log.debug("stop DingtalkTransport stopped");
    }
}