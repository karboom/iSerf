# Websocket 集群

基于 Socket.IO + MessageBus 实现的去中心化 Websocket 集群服务器，支持多节点部署和自动负载均衡。

## 架构概述

- `ContainerServer`（抽象类）承载协议无关的元数据管理和消息总线逻辑
- `WebsocketTransport`（继承 `ITransport`）作为 Socket.IO 传输协议适配层
- 每个节点既是服务端，也是其他节点的代理
- 使用 `IMetaData`（如 Redis）存储元数据（节点列表、Agent 路由信息）
- 使用 `IMessageBus`（如 Pulsar）作为消息总线进行节点间通信
- 支持 Agent 跨节点路由和消息转发

## 代码示例

### 服务端

继承 `ContainerServer` 并实现抽象方法，然后通过 `addTransport()` 挂载 `WebsocketTransport`：

```java
package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.server.transport.WebsocketTransport;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class MyServer extends ContainerServer {

    public MyServer(String clusterIp, Integer port) {
        super(clusterIp, new Pulsar("pulsar://localhost:6650"),
              new RedisSingle("redis://localhost:6379"));
        // 挂载 WebsocketTransport
        addTransport(new WebsocketTransport(this, port) {});
    }

    @Override
    protected Agent createAgent(Context ctx, ObjectNode params) {
        var prompt = params.path("prompt").asText();
        var llm = new OpenAI("qwen-plus", new HashMap<>(),
                System.getenv("OPENAI_API_KEY"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
        return new Agent(UUID.randomUUID().toString(), prompt, llm, null) {};
    }

    @Override
    protected List<Agent> listAgent(Context ctx, ObjectNode params) {
        return new ArrayList<>(localAgents.values());
    }

    @Override
    protected Agent removeAgent(Context ctx, ObjectNode params) {
        var agentId = params.path("agentId").asText();
        return localAgents.remove(agentId);
    }

    public static void main(String[] args) {
        var server = new MyServer("192.168.1.10", 9092);
        server.start();
    }
}
```

客户端使用示例和事件协议详见：[Socket.IO 客户端](SocketIO客户端.md)

## 通信协议

### 用户侧协议（/user）

客户端通过 Socket.IO 连接至 `/user` 命名空间进行 Agent 操作。所有通信使用 `{msgId, body}` 包裹结构，响应通过同名 Socket.IO 事件回传。详见：[Socket.IO 客户端](SocketIO客户端.md)

### 节点间通信（MessageBus）

节点通过 `IMessageBus` 的 publish/subscribe 进行通信。每个节点订阅主题 `{nodeId}-message`，向其他节点发送消息时 publish 到对应节点的主题。

#### 消息格式

消息体为 JSON 数组，包含 4 个元素：

```json
["sourceNodeId", "type", "event", "body"]
```

- `sourceNodeId`: 消息来源节点 ID
- `type`: 消息类型，可选值 `proxy`（请求转发）或 `reverse`（反向推送）
- `event`: 事件名称（如 `agent.create`、`agent.list` 等）
- `body`: 事件数据 JSON 字符串

#### 消息类型

| type | 说明 | 流转方向 |
|------|------|----------|
| `proxy` | 将用户事件代理转发到目标节点 | 源节点 → 目标节点 |
| `reverse` | 将处理结果反向推回源节点 | 目标节点 → 源节点 |

#### 处理流程

**proxy 流程：**

1. 通过 `sendToOtherNode()` 将消息 publish 到 `{targetNodeId}-message` 主题
2. 目标节点的 `listenMessage()` 收到后，根据 `event` 调用 `handleUserEvent()` 处理
3. 如果处理结果非 null，目标节点 publish `reverse` 消息回源节点

**reverse 流程（listenMessage 中处理）：**

1. `agent.list` 事件：走 `ListQueryCollector` 聚合多节点查询结果，收齐后通过 transport 回传给客户端
2. 其他事件：从 `body` 中提取 `agentId`，通过 `agentClient` 缓存查找对应 transport，将消息推送给客户端

### 事件路由规则

| 事件 | 路由规则 |
|------|----------|
| `agent.create` | 直接在本节点创建 Agent，记录到 `metaData` |
| `agent.send` | 先查本地 `localAgents` 缓存，存在则直接发送；否则通过 `metaData.getAgentStay()` 获取驻留节点，proxy 到目标节点 |
| `agent.subscribe` | 先查本地 `localAgents` 缓存，存在则直接订阅；否则缓存 `TransportClientRecord` 到 `agentClient`，proxy 到 Agent 驻留节点 |
| `agent.unsubscribe` | 先查本地 `localAgents` 缓存，存在则直接取消订阅；否则 proxy 到 Agent 驻留节点 |
| `agent.toolCall` | 先查本地 `localAgents` 缓存，存在则直接返回缓存结果；否则 proxy 到 Agent 驻留节点 |
| `agent.list` | 本节点请求时：收集本节点结果，广播到其他节点查询，通过 `ListQueryCollector` 异步聚合后返回；非本节点请求时：直接返回本节点 Agent 列表 |

## 元数据存储

元数据存储由 `IMetaData` 接口的具体实现（如 `RedisSingle`）提供，典型结构如下：

| Key | 类型 | 说明 |
|-----|------|------|
| 节点信息 | 集合/哈希 | 存储所有节点信息（id、ip、port） |
| Agent 驻留节点 | AgentId → NodeId | Agent 当前所在的节点 ID |

## 优雅停机

### 停机流程

1. JVM 收到 SIGTERM/SIGINT 信号时，`Runtime.addShutdownHook` 将 `isShuttingDown` 置为 `true`
2. `WebsocketTransport` 的 AuthListener 检测到 `isShuttingDown`，阻塞 70 秒后拒绝新连接
3. 新到达的 `/user` 命名空间连接被拒绝，Nginx 自动切换到下一个 upstream 节点
4. 调用 `stop()` 时从元数据中移除本节点，停止所有 transport

### Nginx 配合

停机时授权阶段阻塞 70 秒（大于 Nginx 的 `proxy_connect_timeout` 默认值 60 秒），实现无中断滚动更新。

```nginx
upstream iserf_backend {
    server 192.168.1.10:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.11:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.12:9092 max_fails=3 fail_timeout=30s;
}
```

## 负载均衡

### K8S 环境

使用 headless service 通过 DNS 解析获取节点列表：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: iserf-headless
spec:
  clusterIP: None
  selector:
    app: iserf
  ports:
    - port: 9092
      targetPort: 9092
```

### ECS / 虚拟机环境

将 IP 池写入 Nginx：

```nginx
upstream iserf_backend {
    server 192.168.1.10:9092;
    server 192.168.1.11:9092;
    server 192.168.1.12:9092;
}
```

## 故障处理

### 节点故障

1. 调用 `stop()` 或 JVM ShutdownHook 触发时，自动从元数据中移除本节点
2. 受影响的 Agent 通过 `metaData.getAgentStay()` 重新路由

### 数据一致性

- Agent 元数据存储在外部存储（如 Redis），节点重启后可恢复
- 节点异常退出时，内存中的 `localAgents` 数据会丢失
- 建议配合 Redis Persistence 或定期备份