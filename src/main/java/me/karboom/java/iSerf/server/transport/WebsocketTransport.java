package me.karboom.java.iSerf.server.transport;

import com.corundumstudio.socketio.*;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.Server;
import me.karboom.java.iSerf.util.DataUtil;

import java.util.Set;

/**
 * Socket.IO 传输协议适配，将客户端事件委托给 ContainerServer 统一处理。
 * 仅负责 Socket.IO 服务生命周期和 namespace 配置，元数据管理和消息总线逻辑由 ContainerServer 承载。
 */
@Slf4j
public abstract class WebsocketTransport implements ITransport {

    /**
     * Socket.IO 服务实例
     */
    protected SocketIOServer server;

    /**
     * 所属的传输容器，承载协议无关的业务逻辑
     */
    protected Server container;

    /**
     * 监听端口
     */
    protected Integer port;

    /**
     * 传输协议唯一标识
     */
    protected String transportId;

    /**
     * 构造 Websocket 传输适配
     *
     * @param port      监听端口
     * @param container 所属容器
     */
    public WebsocketTransport(Server container, Integer port) {
        this.port = port;
        this.container = container;
        this.transportId = DataUtil.getFlakeId();
    }

    /**
     * 获取 Socket.IO 服务实例
     *
     * @return SocketIOServer 实例
     */
    public SocketIOServer getServer() {
        return server;
    }

    @Override
    public String getTransportId() {
        return transportId;
    }

    @Override
    public void closeClient(Object clientHandle) {
        switch (clientHandle) {
            case SocketIOClient ioClient -> ioClient.disconnect();
            default -> log.warn("closeClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

    @Override
    public void sendToClient(Object clientHandle, String event, String bodyJson) {
        switch (clientHandle) {
            case SocketIOClient ioClient -> ioClient.sendEvent(event, bodyJson);
            default -> log.warn("sendToClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

    /**
     * 连接鉴权回调，子类覆写以提供自定义鉴权逻辑。
     * 通过 client.getHandshakeData() 获取请求头、URL 参数等握手信息。
     * 鉴权通过后可在此方法内将用户信息绑定到 client，供后续 getUser() 使用。
     * 鉴权失败时由子类自行调用 closeClient() 断开连接。
     *
     * @param clientHandle 已连接的客户端句柄
     */
    @Override
    public abstract void authorizeConnection(Object clientHandle);

    /**
     * 根据客户端句柄获取用户唯一标识
     * @param clientHandle 客户端句柄
     * @return 用户唯一标识字符串
     */
    @Override
    public abstract String getUserId(Object clientHandle);

    /**
     * 启动 Socket.IO 服务器
     */
    @Override
    public void start() {
        var config = new Configuration();
        config.setPort(port);
        config.setSocketConfig(new SocketConfig() {{
            setReuseAddress(true);
        }});

        server = new SocketIOServer(config);

        // Agent 命名空间
        var agentNamespace = server.addNamespace("/user");
        setupUserNS(agentNamespace);

        server.start();

        log.debug("start Websocket server started on port %s".formatted(port));
    }

    /**
     * 停止 Socket.IO 服务器
     */
    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            log.debug("stop Websocket server stopped on port %s".formatted(port));
        }
    }

    /**
     * 配置用户命名空间的事件监听，将事件处理委托给 ContainerServer
     *
     * @param userNS 用户命名空间
     */
    protected void setupUserNS(SocketIONamespace userNS) {
        // 已知事件白名单
        var knownEvents = Set.of(
                Server.EVENT_AGENT_CREATE,
                Server.EVENT_AGENT_SEND,
                Server.EVENT_AGENT_SUBSCRIBE,
                Server.EVENT_AGENT_UNSUBSCRIBE,
                Server.EVENT_AGENT_TOOL_CALL,
                Server.EVENT_AGENT_LIST,
                Server.EVENT_AGENT_EDIT,
                Server.EVENT_AGENT_DETAIL,
                Server.EVENT_TEAM_CREATE,
                Server.EVENT_TEAM_LIST,
                Server.EVENT_TEAM_REMOVE,
                Server.EVENT_TEAM_EDIT,
                Server.EVENT_TEAM_DETAIL
        );

        // 连接鉴权，子类自行处理通过/断开
        userNS.addConnectListener(client -> authorizeConnection(client));

        // 拦截未知事件，立即返回错误
        userNS.addEventInterceptor((client, eventName, ackRequest, data) -> {
            if (!knownEvents.contains(eventName)) {
                log.warn("setupUserNS unknown event: %s from %s".formatted(eventName, client.getSessionId()));
                client.sendEvent(eventName, "{\"error\":\"unknown event: " + eventName + "\"}");
            }
        });

        knownEvents.forEach(e -> registerUserEvent(userNS, e));
    }

    /**
     * 将指定事件注册到命名空间并委托给 ContainerServer 处理
     *
     * @param ns    目标 Socket.IO 命名空间
     * @param event 事件名称
     */
    private void registerUserEvent(SocketIONamespace ns, String event) {
        ns.addEventListener(event, String.class, (client, dataJson, ackSender) -> {
            var ctx = Server.Context.builder()
                    .transport(this)
                    .client(client)
                    .serverId(container.id)
                    .server(container)
                    .isInternal(false)
                    .build();
            container.handleUserEvent(ctx, event, dataJson);
        });
    }
}