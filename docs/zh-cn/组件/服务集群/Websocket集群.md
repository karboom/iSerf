# Websocket 集群

基于 Socket.IO 实现的去中心化 Websocket 集群服务器，支持多节点部署和自动负载均衡。

## 架构概述

- 每个节点既是服务端，也是其他节点的代理
- 使用 Redis 存储元数据（节点列表、Agent 路由信息）
- 使用 Pulsar 作为消息总线进行节点间通信
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

    public MyWebsocketServer(String ip, Integer port, IMessageBus messageBus, IMetaData metaData) {
        super(ip, port, messageBus, metaData);
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

### 客户端（socket.io.js）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>Websocket 集群客户端示例</title>
    <script src="https://cdn.socket.io/4.7.2/socket.io.min.js"></script>
</head>
<body>
    <h1>Websocket 集群客户端</h1>
    <div id="log"></div>

    <script>
        // 连接 /user 命名空间
        const socket = io('http://192.168.1.10:9092/user', {
            transports: ['websocket']
        });

        const log = (msg) => {
            document.getElementById('log').innerHTML += `<p>${msg}</p>`;
            console.log(msg);
        };

        // 监听连接
        socket.on('connect', () => {
            log('已连接服务器');
        });

        // 监听错误
        socket.on('connect_error', (err) => {
            log(`连接错误：${err.message}`);
        });

        // 监听消息推送
        socket.on('message', (data) => {
            log(`收到消息：${JSON.stringify(data)}`);
        });

        // 创建 Agent
        function createAgent(prompt) {
            return new Promise((resolve) => {
                socket.emit('agent/create', JSON.stringify({ prompt }), (response) => {
                    const res = JSON.parse(response);
                    log(`Agent 已创建：${res.agentId}`);
                    resolve(res.agentId);
                });
            });
        }

        // 订阅 Agent
        function activeAgent(agentId) {
            return new Promise((resolve) => {
                socket.emit('agent/active', JSON.stringify({ agentId }), (response) => {
                    const res = JSON.parse(response);
                    log(`Agent 已激活：${res.success}`);
                    resolve(res.success);
                });
            });
        }

        // 发送消息
        function sendMessage(agentId, event) {
            socket.emit('agent/send', JSON.stringify({ agentId, event }), () => {
                log('消息已发送');
            });
        }

        // 取消订阅
        function leaveAgent(agentId) {
            socket.emit('agent/leave', JSON.stringify({ agentId }), (response) => {
                const res = JSON.parse(response);
                log(`已取消订阅：${res.success}`);
            });
        }

        // 使用示例
        (async () => {
            const agentId = await createAgent('你是一个助手');
            await activeAgent(agentId);
            sendMessage(agentId, { type: 'message', content: '你好' });
        })();
    </script>
</body>
</html>
```


## 通信协议

### 命名空间

协议使用 Socket.IO，分为两个命名空间：

- `/user` - 用户侧协议，客户端连接至此命名空间进行 Agent 相关操作
- `/sys` - 服务侧协议，集群节点间通信使用

### 用户侧协议（/user）

#### 客户端发送事件

| 事件 | 请求格式 | 响应格式 | 说明 |
|------|----------|----------|------|
| `agent/create` | `{"prompt":"xxx"}` | `{"agentId":"xxx"}` | 创建 Agent，随机路由到节点 |
| `agent/send` | `{"agentId":"xxx","event":{}}` | - | 向 Agent 发送消息 |
| `agent/active` | `{"agentId":"xxx"}` | `{"success":true}` | 订阅 Agent，接收其消息推送 |
| `agent/leave` | `{"agentId":"xxx"}` | `{"success":true}` | 取消订阅 Agent |
| `agent/toolCall` | `{"agentId":"xxx","toolCallId":"xxx"}` | `{"result":{}}` | 调用 Agent 工具缓存 |

#### 服务端推送事件

| 事件 | 数据格式 | 说明 |
|------|----------|------|
| `agent/message` | `{"agentId":"xxx","item":{}}` | 推送 Agent 产生的消息 |

### 服务侧协议（/sys）

#### 节点间事件

| 事件 | 数据格式 | 说明 |
|------|----------|------|
| `alive` | `{"ip":"xxx","port":9092}` | 节点心跳广播，每 5 秒发送一次 |
| `proxy` | `{"event":"xxx","body":{}}` | 请求代理转发到其他节点 |
| `reverse` | `{"event":"xxx","body":{}}` | 反向推送消息给客户端 |

### 消息路由规则

事件名称格式：`namespace/name`

| 事件 | 路由规则 |
|------|----------|
| `agent/create` | 直接在本节点创建 Agent，记录到元数据 |
| `agent/send` | 先查 `otherAgentNode` 缓存，命中则发往对应节点；未命中则广播给所有非本节点，首个响应的节点会被缓存 |
| `agent/active` | 先查本地 `agents` 缓存，存在则直接订阅；否则转发请求 |
| `agent/leave` | 先查本地 `agents` 缓存，存在则直接取消订阅；否则转发请求 |
| `agent/toolCall` | 先查本地 `agents` 缓存，存在则直接返回结果；否则转发请求 |

### 消息封装格式

- 跨节点转发的消息封装为：`{"event":"xxx","body":{}}`
- body 中会自动添加 `source` 字段标识来源节点：`"source": "ip:port"`

### 节点发现与维护

- 节点列表通过 `getNodes()` 抽象方法获取（由子类实现，可从 K8S Headless Service 或 ECS IP 池获取）
- 每 5 秒定时调用 `getNodes()` 更新节点列表
- 每 5 秒向其他节点广播 `alive` 心跳消息
- 节点间建立 WebSocket 连接保持通信

## Redis 数据结构

| Key 前缀 | 类型 | 说明 |
|----------|------|------|
| `Nodes` | Hash | 存储所有节点信息，{id: "ip:port"} |
| `AS:{agentId}` | String | Agent 驻留节点 ID |
| `AN:{agentId}` | List | 订阅该 Agent 的节点列表 |

## 性能测试

### 测试环境

- 节点数：3
- Redis：单机版
- Pulsar：单机版

### 测试指标

| 指标 | 目标值 | 实测值 |
|------|--------|--------|
| 单节点连接数 | 10,000 | - |
| 消息延迟 (P99) | < 100ms | - |
| 跨节点转发延迟 | < 200ms | - |
| 心跳间隔 | 5s | - |

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

1. 心跳检测超时（30 秒无心跳）
2. 自动从 Redis Nodes 中移除故障节点
3. 重新路由受影响的 Agent

### 数据一致性

- Agent 元数据存储在 Redis，节点重启后可恢复
- 节点异常退出时，未持久化的数据可能丢失
- 建议使用 Redis Persistence 或定期备份