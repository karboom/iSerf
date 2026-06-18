# WebsocketTransport

基于 Socket.IO 的传输协议实现，将客户端事件委托给 Server 统一处理。仅负责 Socket.IO 服务生命周期和 namespace 配置，元数据管理和消息总线逻辑由 Server 承载。

## 核心职责

- Socket.IO 服务生命周期管理（启动/停止）
- `/user` 命名空间配置和事件注册
- 连接鉴权（子类覆写 `authorizeConnection`）
- 客户端消息收发（`sendToClient` / `closeClient`）

## 代码示例

继承 `WebsocketTransport` 并实现鉴权逻辑，然后通过 `addTransport()` 挂载到 Server：

```java
server.addTransport(new WebsocketTransport(server, 9092) {
    @Override
    public void authorizeConnection(Object clientHandle) {
        var client = (SocketIOClient) clientHandle;
        var headers = client.getHandshakeData().getHttpHeaders();
        var token = headers.get("Authorization");
        // 鉴权逻辑...
        // 失败时调用 closeClient(client) 断开连接
    }

    @Override
    public String getUserId(Object clientHandle) {
        var client = (SocketIOClient) clientHandle;
        // 从 client 获取用户标识
        return "user-xxx";
    }
});
```

## 命名空间

### /user

客户端通过 Socket.IO 连接至 `/user` 命名空间进行 Agent 操作。

- 连接时触发 `authorizeConnection` 进行鉴权
- 注册所有已知事件到命名空间，未知事件返回标准错误响应
- 事件处理委托给 `Server.handleUserEvent()`

客户端使用示例和事件协议详见：[Socket.IO 客户端](SocketIO客户端.md)

## 优雅停机

### 停机流程

1. Server 设置 `isShuttingDown = true`
2. `authorizeConnection` 检测到停机状态，阻塞 70 秒后拒绝新连接
3. 新到达的 `/user` 命名空间连接被拒绝，Nginx 自动切换到下一个 upstream 节点
4. Server 调用 `stop()` 停止 Socket.IO 服务

### Nginx 配合

停机时授权阶段阻塞 70 秒（大于 Nginx 的 `proxy_connect_timeout` 默认值 60 秒），实现无中断滚动更新。

```nginx
upstream iserf_backend {
    server 192.168.1.10:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.11:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.12:9092 max_fails=3 fail_timeout=30s;
}
```
