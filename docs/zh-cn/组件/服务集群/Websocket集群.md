# Websocket 集群

基于 Socket.IO 实现的去中心化 Websocket 集群服务器，支持多节点部署和自动负载均衡。

## 架构概述

- 每个节点既是服务端，也是其他节点的代理
- 使用 Redis 存储元数据（节点列表、Agent 路由信息）
- 使用 MessageBus 作为消息总线进行节点间通信（如 Pulsar）
- 支持 Agent 跨节点路由和消息转发

## 代码示例

### 服务端

继承 `Websocket` 类并实现 `createAgent` 方法：

```java
package me.karboom.java.iSerf.server;

import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.server.messageBus.IMessageBus;
import me.karboom.java.iSerf.server.messageBus.Pulsar;
import me.karboom.java.iSerf.server.metaData.IMetaData;
import me.karboom.java.iSerf.server.metaData.RedisSingle;
import me.karboom.java.iSerf.server.protocol.Websocket;
import me.karboom.java.iSerf.tool.Loader;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.UUID;

public class MyWebsocketServer extends Websocket {

    @Override
    public Agent createAgent(ObjectNode params) {
        var prompt = params.path("prompt").asText();
        var tools = new Loader(2000).fromIFunction("/path/to/functions", "toolName", null);
        
        return new Agent(
            UUID.randomUUID().toString(), 
            prompt, 
            new OpenAI("qwen-plus", new HashMap<>(), System.getenv("OPENAI_API_KEY"), 
                      "https://dashscope.aliyuncs.com/compatible-mode/v1", 3), 
            tools
        ) {};
    }

    public MyWebsocketServer(String clusterIp, Integer port, IMessageBus messageBus, IMetaData metaData) {
        super(clusterIp, port, messageBus, metaData);
    }

    public static void main(String[] args) {
        var server = new MyWebsocketServer(
            "192.168.1.10", 
            9092,
            new Pulsar("pulsar://localhost:6650"),
            new RedisSingle("redis://localhost:6379")
        );
        server.start();
    }
}
```

客户端使用示例和事件协议详见：[Socket.IO 客户端](SocketIO客户端.md)


## 通信协议

### 用户侧协议（/user）

客户端通过 Socket.IO 连接至 `/user` 命名空间进行 Agent 操作，详见：[Socket.IO 客户端](SocketIO客户端.md)

### 节点间通信（MessageBus）

节点通过 MessageBus 的 publish/subscribe 进行通信。每个节点订阅主题 `{nodeId}-message`，向其他节点发送消息时 publish 到对应节点的主题。

#### 消息格式

消息体为管道符 `|` 分隔的 4 段字符串：

```
{sourceNodeId}|{type}|{event}|{body}
```

- `sourceNodeId`: 消息来源节点 ID
- `type`: 消息类型，可选值 `proxy`（请求转发）或 `reverse`（反向推送）
- `event`: 事件名称
- `body`: 事件数据 JSON 字符串

#### 消息类型

| type | 说明 | 流转方向 |
|------|------|----------|
| `proxy` | 将用户事件代理转发到目标节点 | 源节点 → 目标节点 |
| `reverse` | 将处理结果反向推回源节点 | 目标节点 → 源节点 |

#### 处理流程

**proxy 流程：**

1. 源节点 publish 消息到 `{targetNodeId}-message` 主题
2. 目标节点收到后，根据 `event` 查找对应的 `userEventHandler` 处理
3. 如果处理结果非 null，目标节点 publish `reverse` 消息回源节点

**reverse 流程：**

1. 源节点收到 `reverse` 消息后，从 `body` 中提取 `agentId`
2. 通过 `agentClient` 缓存查找对应的客户端 Socket.IO session
3. 将 `body` 通过 `/user` 命名空间直接推送给客户端

### 消息路由规则

| 事件 | 路由规则 |
|------|----------|
| `agent/create` | 直接在本节点创建 Agent，记录到元数据 |
| `agent/send` | 先查本地 `localAgents` 缓存，存在则直接发送；否则通过 `metaData.getAgentStay()` 获取驻留节点，精确路由到目标节点 |
| `agent/active` | 先查本地 `localAgents` 缓存，存在则直接订阅；否则通过 MessageBus 转发到 Agent 驻留节点 |
| `agent/leave` | 先查本地 `localAgents` 缓存，存在则直接取消订阅；否则通过 MessageBus 转发到 Agent 驻留节点 |
| `agent/toolCall` | 先查本地 `localAgents` 缓存，存在则直接返回缓存结果；否则通过 MessageBus 转发到 Agent 驻留节点 |

## Redis 数据结构

元数据存储结构由 `IMetaData` 接口的具体实现决定（如 `RedisSingle`），典型结构如下：

| Key 前缀 | 类型 | 说明 |
|----------|------|------|
| `Nodes` | Hash | 存储所有节点信息，{id: "ip:port"} |
| `AS:{agentId}` | String | Agent 驻留节点 ID |
| `AN:{agentId}` | List | 订阅该 Agent 的节点列表 |

## 优雅停机

### 停机流程

1. JVM 收到 SIGTERM/SIGINT 信号时，ShutdownHook 将 `isShuttingDown` 置为 `true`
2. 新到达的 `/user` 命名空间连接和事件请求被拦截，返回 `{"error":"server is shutting down"}`
3. 通过 MessageBus 到达的 `proxy` 请求同样被拦截，反向推送错误信息
4. 调用 `stop()` 方法时会同时从元数据中移除本节点

### Nginx 配合

停机时，授权阶段会阻塞 70 秒（大于 Nginx 的 `proxy_connect_timeout` 默认值 60 秒），使得 Nginx 连接超时后自动切换到下一个 upstream 节点，实现无中断滚动更新。

```nginx
upstream iserf_backend {
    server 192.168.1.10:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.11:9092 max_fails=3 fail_timeout=30s;
    server 192.168.1.12:9092 max_fails=3 fail_timeout=30s;
}
```

## 性能测试

### 测试环境

- 节点数：3
- Redis：单机版
- MessageBus：单机版（Pulsar）

### 测试指标

| 指标 | 目标值 | 实测值 |
|------|--------|--------|
| 单节点连接数 | 10,000 | - |
| 消息延迟 (P99) | < 100ms | - |
| 跨节点转发延迟 | < 200ms | - |

### 测试脚本

```javascript
// 使用 k6 进行压力测试
import ws from 'k6/ws';
import { check } from 'k6';

export default function () {
  const url = 'ws://localhost:9092/user';
  
  const response = ws.connect(url, {}, function (socket) {
    socket.on('open', function () {
      socket.send(JSON.stringify({ event: 'agent/create', data: { prompt: 'test' } }));
    });
    
    socket.on('message', function (message) {
      console.log('Received:', message);
    });
  });
  
  check(response, { 'status is 101': (r) => r && r.status === 101 });
}
```

## 负载均衡

### K8S 环境

负载均衡使用 headless service 服务域名，通过 DNS 解析获取 nodes：

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

### ECS 环境

直接将 IP 池所有地址写入 Nginx，直接将 IP 池数组写入 nodes：

```nginx
upstream iserf_backend {
    server 192.168.1.10:9092;
    server 192.168.1.11:9092;
    server 192.168.1.12:9092;
}
```

## 部署配置

### 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `OPENAI_API_KEY` | OpenAI API 密钥 | - |
| `PULSAR_URL` | Pulsar 连接地址 | pulsar://localhost:6650 |
| `REDIS_URL` | Redis 连接地址 | redis://localhost:6379 |
| `SERVER_PORT` | 服务端口 | 9092 |
| `CLUSTER_IP` | 集群 IP | 0.0.0.0 |

### Docker 部署

```dockerfile
FROM openjdk:17-slim

WORKDIR /app
COPY build/libs/isserf.jar .

ENV PULSAR_URL=pulsar://pulsar:6650
ENV REDIS_URL=redis://redis:6379
ENV SERVER_PORT=9092

EXPOSE 9092

CMD ["java", "-jar", "isserf.jar"]
```

## 故障处理

### 节点故障

1. 调用 `stop()` 或 JVM ShutdownHook 触发时，自动从元数据中移除节点
2. 受影响的 Agent 通过 `metaData.getAgentStay()` 重新路由

### 数据一致性

- Agent 元数据存储在 Redis，节点重启后可恢复
- 节点异常退出时，未持久化的数据可能丢失
- 建议使用 Redis Persistence 或定期备份