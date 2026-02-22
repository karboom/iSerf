# Websocket 集群
提供一个弹性伸缩的集群服务，每个节点是对等的，即提供服务，也充当路由。扩缩容无需迁移节点内容。外层可以增加负载均衡来统一入口。

![Websocket 集群架构](https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSerf/%E6%9E%B6%E6%9E%84%E5%9B%BE-%E6%9C%8D%E5%8A%A1%E9%9B%86%E7%BE%A4.webp)



## 代码示例

### 服务端

继承 `Websocket` 类并实现 `getNodes()` 方法：

```java
import me.karboom.java.iSerf.server.Websocket;
import java.util.List;

public class MyWebsocketServer extends Websocket {
    public MyWebsocketServer(String ip, Integer port) {
        super(ip, port);
    }

    @Override
    public List<String> getNodes() {
        // K8S 环境：通过 headless service 域名解析获取节点列表
        // return List.of("iSerf.default.svc.cluster.local");
        
        // ECS 环境：直接返回 IP 池
        return List.of("192.168.1.10:9092", "192.168.1.11:9092", "192.168.1.12:9092");
    }

    public static void main(String[] args) {
        var server = new MyWebsocketServer("192.168.1.10", 9092);
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

#### 服务端推送事件

| 事件 | 数据格式 | 说明 |
|------|----------|------|
| `message` | `{"agentId":"xxx","item":{}}` | 推送 Agent 产生的消息 |

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
| `agent/create` | 随机选择非本节点的目标节点转发，如目标为自身则直接处理 |
| `agent/send` | 先查 `otherAgentNode` 缓存，命中则发往对应节点；未命中则广播给所有非本节点，首个响应的节点会被缓存 |
| `agent/active` | 先查本地 `agents` 缓存，存在则直接订阅；否则转发请求 |
| `agent/leave` | 先查本地 `agents` 缓存，存在则直接取消订阅；否则转发请求 |

### 消息封装格式

- 跨节点转发的消息封装为：`{"event":"xxx","body":{}}`
- body 中会自动添加 `source` 字段标识来源节点：`"source": "ip:port"`

### 节点发现与维护

- 节点列表通过 `getNodes()` 抽象方法获取（由子类实现，可从 K8S Headless Service 或 ECS IP 池获取）
- 每 5 秒定时调用 `getNodes()` 更新节点列表
- 每 5 秒向其他节点广播 `alive` 心跳消息
- 节点间建立 WebSocket 连接保持通信

## 性能测试
Todo

## 负载均衡

- K8S

负载均衡使用 headless service 服务域名，通过 dns 解析获取 nodes

- ECS

直接将 ip 池所有地址写入 Nginx，直接将 ip 池数组写入 nodes