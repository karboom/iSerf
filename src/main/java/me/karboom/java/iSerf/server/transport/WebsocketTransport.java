package me.karboom.java.iSerf.server.transport;

import com.corundumstudio.socketio.*;
import com.corundumstudio.socketio.listener.EventInterceptor;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.server.ContainerServer;
import me.karboom.java.iSerf.util.DataUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Socket.IO 传输协议适配，将客户端事件委托给 ContainerServer 统一处理。
 * 仅负责 Socket.IO 服务生命周期和 namespace 配置，元数据管理和消息总线逻辑由 ContainerServer 承载。
 */
@Slf4j
public abstract class WebsocketTransport implements ITransport {

    /**
     * 关闭状态下的拒绝消息
     */
    private static final String SHUTTING_DOWN_ERROR = "{\"error\":\"server is shutting down\"}";

    /**
     * Socket.IO 服务实例
     */
    protected SocketIOServer server;

    /**
     * 所属的传输容器，承载协议无关的业务逻辑
     */
    protected ContainerServer container;

    /**
     * 监听端口
     */
    protected Integer port;

    /**
     * 传输协议唯一标识
     */
    protected String transportId;

    /**
     * 用户授权监听链
     */
    public List<AuthorizationListener> userAuthListener = new ArrayList<>();

    /**
     * 系统授权监听链
     */
    public List<AuthorizationListener> sysAuthListener = new ArrayList<>();

    /**
     * 构造 Websocket 传输适配
     *
     * @param port      监听端口
     * @param container 所属容器
     */
    public WebsocketTransport(ContainerServer container, Integer port) {
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
    public void sendToClient(Object clientHandle, String event, String bodyJson) {
        switch (clientHandle) {
            case SocketIOClient ioClient -> ioClient.sendEvent(event, bodyJson);
            default -> log.warn("sendToClient unsupported clientHandle type: %s".formatted(clientHandle.getClass().getName()));
        }
    }

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

        // 系统授权链：优先检查关闭状态
        sysAuthListener.addFirst(data -> {
            if (container.isShuttingDown()) {
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
        // 添加事件拦截器，关闭状态下统一拒绝
        userNS.addEventInterceptor(new EventInterceptor() {
            @Override
            public void onEvent(com.corundumstudio.socketio.transport.NamespaceClient client, String eventName, java.util.List<Object> args, AckRequest ackRequest) {
                if (container.isShuttingDown()) {
                    ackRequest.sendAckData(SHUTTING_DOWN_ERROR);
                }
            }
        });

        // 注册用户侧事件，委托给 ContainerServer 处理
        userNS.addEventListener(ContainerServer.EVENT_AGENT_CREATE, String.class, (client, dataJson, ackSender) -> {
            var ctx = ContainerServer.Context.builder()
                    .transport(WebsocketTransport.this)
                    .client(client)
                    .build();
            var result = container.handleUserEvent(ContainerServer.EVENT_AGENT_CREATE, ctx, dataJson);
            if (result != null) {
                ackSender.sendAckData(result);
            }
        });

        userNS.addEventListener(ContainerServer.EVENT_AGENT_SEND, String.class, (client, dataJson, ackSender) -> {
            var ctx = ContainerServer.Context.builder()
                    .transport(WebsocketTransport.this)
                    .client(client)
                    .build();
            var result = container.handleUserEvent(ContainerServer.EVENT_AGENT_SEND, ctx, dataJson);
            if (result != null) {
                ackSender.sendAckData(result);
            }
        });
        userNS.addEventListener(ContainerServer.EVENT_AGENT_SUBSCRIBE, String.class, (client, dataJson, ackSender) -> {
            var ctx = ContainerServer.Context.builder()
                    .transport(WebsocketTransport.this)
                    .client(client)
                    .build();
            var result = container.handleUserEvent(ContainerServer.EVENT_AGENT_SUBSCRIBE, ctx, dataJson);
            if (result != null) {
                ackSender.sendAckData(result);
            }
        });
        userNS.addEventListener(ContainerServer.EVENT_AGENT_UNSUBSCRIBE, String.class, (client, dataJson, ackSender) -> {
            var ctx = ContainerServer.Context.builder()
                    .transport(WebsocketTransport.this)
                    .client(client)
                    .build();
            var result = container.handleUserEvent(ContainerServer.EVENT_AGENT_UNSUBSCRIBE, ctx, dataJson);
            if (result != null) {
                ackSender.sendAckData(result);
            }
        });
        userNS.addEventListener(ContainerServer.EVENT_AGENT_TOOL_CALL, String.class, (client, dataJson, ackSender) -> {
            var ctx = ContainerServer.Context.builder()
                    .transport(WebsocketTransport.this)
                    .client(client)
                    .build();
            var result = container.handleUserEvent(ContainerServer.EVENT_AGENT_TOOL_CALL, ctx, dataJson);
            if (result != null) {
                ackSender.sendAckData(result);
            }
        });
    }
}