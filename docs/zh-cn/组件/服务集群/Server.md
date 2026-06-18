# Server

通用传输容器，承载协议无关的元数据管理和消息总线逻辑。可挂载多个 `ITransport` 实现（Websocket、Mqtt、Dingtalk 等），统一管理生命周期和集群通信。

## 核心职责

- 事件分发：接收各 Transport 的客户端事件，统一路由处理
- 集群通信：通过 `IMessageBus` 实现节点间消息转发
- 元数据管理：通过 `IMetaData` 维护节点和 Agent/Team 路由信息
- 生命周期管理：通过 `IAgentLifecycle` 和 `ITeamLifecycle` 管理业务实体

## 架构概述

- `Server`（抽象类）承载协议无关的元数据管理和消息总线逻辑
- `AgentLifecycle`（抽象类）定义 Agent 的创建、查询、删除生命周期
- `TeamLifecycle`（抽象类）定义 Team 的创建、查询、删除生命周期
- `ITransport` 接口定义传输协议适配层，可挂载多个实现
- 每个节点既是服务端，也是其他节点的代理
- 使用 `IMetaData`（如 Redis）存储元数据（节点列表、Agent 路由信息）
- 使用 `IMessageBus`（如 Pulsar）作为消息总线进行节点间通信
- 支持 Agent 跨节点路由和消息转发

## 代码示例

实现 `AgentLifecycle` 和 `TeamLifecycle`，实例化 `Server` 子类，然后通过 `addTransport()` 挂载传输协议：

```java
package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.team.Team;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.server.transport.WebsocketTransport;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class MyAgentLifecycle extends AgentLifecycle {

    @Override
    public Agent createAgent(Context ctx, ObjectNode params) {
        var prompt = params.path("prompt").asText();
        var llm = new OpenAI("qwen-plus", new HashMap<>(),
                System.getenv("OPENAI_API_KEY"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 3);
        return new Agent(UUID.randomUUID().toString(), prompt, llm, null) {};
    }

    @Override
    public List<Agent> listAgent(Context ctx, ObjectNode params) {
        return new ArrayList<>(localAgents.values());
    }

    @Override
    public Agent removeAgent(Context ctx, ObjectNode params) {
        var agentId = params.path("agentId").asText();
        return localAgents.remove(agentId);
    }
}

public class MyTeamLifecycle extends TeamLifecycle {

    @Override
    public Team createTeam(Context ctx, ObjectNode params) {
        // 从 params 解析 leader 和 members
        return null;
    }

    @Override
    public List<Team> listTeam(Context ctx, ObjectNode params) {
        return new ArrayList<>(localTeams.values());
    }

    @Override
    public Team removeTeam(Context ctx, ObjectNode params) {
        var teamId = params.path("teamId").asText();
        return localTeams.remove(teamId);
    }
}

public class Main {
    public static void main(String[] args) {
        var lifecycle = new MyAgentLifecycle();
        var teamLifecycle = new MyTeamLifecycle();
        var server = new ContainerServer("192.168.1.10",
                new Pulsar("pulsar://localhost:6650"),
                new RedisSingle("redis://localhost:6379"),
                lifecycle,
                teamLifecycle);
        // 挂载传输协议
        server.addTransport(new WebsocketTransport(server, 9092) {});
        server.start();
    }
}
```

## 事件处理

Server 统一处理所有客户端事件，根据事件名分发到对应的 handle 方法。

### 支持的事件

| 事件 | 说明 |
|------|------|
| `agent.activate` | 从持久化存储加载 Agent 到内存 |
| `agent.deactivate` | 将 Agent 从内存移除回持久化存储 |
| `agent.create` | 创建 Agent |
| `agent.send` | 向 Agent 发送消息 |
| `agent.subscribe` | 订阅 Agent 消息流 |
| `agent.unsubscribe` | 取消订阅 Agent |
| `agent.toolCall` | 调用 Agent 工具缓存 |
| `agent.list` | 查询 Agent 列表 |
| `agent.edit` | 编辑 Agent |
| `agent.detail` | 获取 Agent 详情 |
| `team.create` | 创建 Team |
| `team.list` | 查询 Team 列表 |
| `team.remove` | 删除 Team |
| `team.edit` | 编辑 Team |
| `team.detail` | 获取 Team 详情 |

### 事件路由规则

| 事件 | 路由规则 |
|------|----------|
| `agent.activate` | 先检查本地是否已存在，不存在则从持久化存储加载到 `localAgents`，记录到 `metaData` |
| `agent.deactivate` | 先查本地 `localAgents` 缓存，存在则同步持久化并移除；否则 proxy 到 Agent 驻留节点 |
| `agent.create` | 直接在本节点创建 Agent，记录到 `metaData` |
| `agent.send` | 先查本地 `localAgents` 缓存，存在则直接发送；否则通过 `metaData.getAgentStay()` 获取驻留节点，proxy 到目标节点 |
| `agent.subscribe` | 先查本地 `localAgents` 缓存，存在则直接订阅；否则缓存 `TransportClientRecord` 到 `agentClient`，proxy 到 Agent 驻留节点 |
| `agent.unsubscribe` | 先查本地 `localAgents` 缓存，存在则直接取消订阅；否则 proxy 到 Agent 驻留节点 |
| `agent.toolCall` | 先查本地 `localAgents` 缓存，存在则直接返回缓存结果；否则 proxy 到 Agent 驻留节点 |
| `agent.list` | 通过底层共享存储查找，单节点即可返回全局结果 |
| `agent.edit` | 通过底层共享存储查找并编辑，单节点即可返回全局结果 |
| `agent.detail` | 通过底层共享存储查找，单节点即可返回全局结果 |
| `team.create` | 直接在本节点创建 Team，记录到 `metaData` |
| `team.list` | 通过底层共享存储查找，单节点即可返回全局结果 |
| `team.remove` | 通过底层共享存储查找并删除，同步清理本地缓存和 `metaData` |
| `team.edit` | 通过底层共享存储查找并编辑，单节点即可返回全局结果 |
| `team.detail` | 先查本地 `localTeams` 缓存，存在则直接返回；否则缓存 `TransportClientRecord` 到 `teamClient`，proxy 到 Team 驻留节点 |

## 通信协议

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

1. `team.detail` 事件：从 `teamClient` 查找对应 transport，将消息推送给客户端
2. 其他事件：从 `body` 中提取 `agentId`，通过 `agentClient` 缓存查找对应 transport，将消息推送给客户端

## 错误处理

Server 统一处理所有业务异常，通过 `buildErrorResponse()` 构建标准错误响应：

```json
{"msgId": "", "body": {"error": "错误信息"}}
```

- 业务异常：由 `handleUserEvent()` 捕获，调用 `exceptionHandler()` 子类实现自定义异常转换
- 协议级错误：由各 Transport 实现处理（如未知事件拦截）
- 停机拒绝：`isShuttingDown` 为 true 时，返回错误并断开连接

## 元数据存储

元数据存储由 `IMetaData` 接口的具体实现（如 `RedisSingle`）提供，典型结构如下：

| Key | 类型 | 说明 |
|-----|------|------|
| 节点信息 | 集合/哈希 | 存储所有节点信息（id、ip、port） |
| Agent 驻留节点 | AgentId → NodeId | Agent 当前所在的节点 ID |
| Team 驻留节点 | TeamId → NodeId | Team 当前所在的节点 ID |

## 优雅停机

### 停机流程

1. JVM 收到 SIGTERM/SIGINT 信号时，`Runtime.addShutdownHook` 将 `isShuttingDown` 置为 `true`
2. 各 Transport 的授权监听检测到 `isShuttingDown`，阻塞后拒绝新连接
3. 新连接被拒绝，负载均衡器自动切换到其他节点
4. 调用 `stop()` 时从元数据中移除本节点，停止所有 transport

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
