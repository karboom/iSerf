# Socket.IO 客户端

Websocket 集群协议使用 Socket.IO，客户端连接至 `/user` 命名空间进行 Agent 相关操作。

## 通信协议

客户端与服务端通信采用 `{msgId, body}` 结构包裹请求和响应数据：

- **请求**: `{"msgId":"<唯一请求ID>","body":{...具体参数}}`
- **成功响应**: `{"msgId":"<对应请求ID>","body":{"data":{...}}}`
- **错误响应**: `{"msgId":"","body":{"error":"错误信息"}}`

响应通过同名事件回传（非 ack 回调），客户端需通过 `socket.on(eventName, callback)` 监听响应。

## 代码示例（socket.io.js）

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

        // 生成唯一请求ID
        function generateMsgId() {
            return 'msg_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        }

        // 监听连接
        socket.on('connect', () => {
            log('已连接服务器');
        });

        // 监听错误
        socket.on('connect_error', (err) => {
            log(`连接错误：${err.message}`);
        });

        // 监听消息推送
        socket.on('agent.message', (data) => {
            const msg = JSON.parse(data);
            log(`收到消息：${JSON.stringify(msg)}`);
        });

        // 通用请求方法
        function request(event, body) {
            return new Promise((resolve, reject) => {
                const msgId = generateMsgId();
                const wrapped = JSON.stringify({ msgId, body });

                // 监听同名事件响应
                const handler = (data) => {
                    const response = JSON.parse(data);
                    if (response.msgId === msgId) {
                        socket.off(event, handler);
                        if (response.body && response.body.error) {
                            reject(new Error(response.body.error));
                        } else {
                            resolve(response.body && response.body.data);
                        }
                    }
                };
                socket.on(event, handler);

                socket.emit(event, wrapped);
            });
        }

        // 创建 Agent
        function createAgent(prompt) {
            return request('agent.create', { prompt }).then(data => data.agentId);
        }

        // 订阅 Agent
        function subscribeAgent(agentId) {
            return request('agent.subscribe', { agentId });
        }

        // 发送消息
        function sendMessage(agentId, event) {
            return request('agent.send', { agentId, event });
        }

        // 取消订阅
        function unsubscribeAgent(agentId) {
            return request('agent.unsubscribe', { agentId });
        }

        // 查询 Agent 列表
        function listAgents(filter) {
            return request('agent.list', filter || {}).then(data => data.agents || []);
        }

        // 调用工具缓存
        function toolCall(agentId, toolCallId) {
            return request('agent.toolCall', { agentId, toolCallId });
        }

        // 使用示例
        (async () => {
            try {
                const agentId = await createAgent('你是一个助手');
                log(`Agent 已创建：${agentId}`);
                await subscribeAgent(agentId);
                log('已订阅 Agent');
                sendMessage(agentId, { type: 'MESSAGE', priority: 1, message: { type: 'TEXT', text: '你好' } });
            } catch (err) {
                log(`错误：${err.message}`);
            }
        })();
    </script>
</body>
</html>
```

## 客户端事件

### 发送事件

| 事件 | 请求 body | 响应 data | 说明 |
|------|----------|----------|------|
| `agent.create` | `{"prompt":"xxx"}` | `{"agentId":"xxx"}` | 创建 Agent，在本节点直接创建 |
| `agent.send` | `{"agentId":"xxx","event":{...}}` | - | 向 Agent 发送消息，event 结构见下方说明 |
| `agent.subscribe` | `{"agentId":"xxx"}` | - | 订阅 Agent，接收其消息推送 |
| `agent.unsubscribe` | `{"agentId":"xxx"}` | - | 取消订阅 Agent |
| `agent.toolCall` | `{"agentId":"xxx","toolCallId":"xxx"}` | `{...}` | 调用 Agent 工具缓存，返回工具调用结果的 ObjectNode |
| `agent.list` | `{...查询条件}` | `{"agents":[...]}` | 查询 Agent 列表 |

#### `agent.send` event 结构

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 事件类型：`MESSAGE` / `ORGANIZE_MEMORY` / `RECOVERY` |
| `priority` | Integer | ✅ | 优先级，数值越小优先级越高 |
| `message` | Object | 条件必填 | 当 `type` 为 `MESSAGE` 时必填 |

#### message 结构（当 type=MESSAGE 时）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 消息类型：`TEXT` / `IMAGE` / `VIDEO` / `AUDIO` / `MIXED` |
| `text` | String | - | 文本内容 |
| `files` | String[] | - | 图片/文件列表（支持文件路径、base64、http链接） |
| `video` | String | - | 视频地址 |
| `audio` | String | - | 音频地址 |

#### 示例

```json
// 创建 Agent
{
  "msgId": "msg_001",
  "body": {
    "prompt": "你是一个助手"
  }
}

// 发送文本消息
{
  "msgId": "msg_002",
  "body": {
    "agentId": "xxx",
    "event": {
      "type": "MESSAGE",
      "priority": 1,
      "message": {
        "type": "TEXT",
        "text": "你好"
      }
    }
  }
}

// 发送带图片的消息
{
  "msgId": "msg_003",
  "body": {
    "agentId": "xxx",
    "event": {
      "type": "MESSAGE",
      "priority": 1,
      "message": {
        "type": "IMAGE",
        "text": "描述这张图片",
        "files": ["https://example.com/image.png"]
      }
    }
  }
}

// 触发记忆整理
{
  "msgId": "msg_004",
  "body": {
    "agentId": "xxx",
    "event": {
      "type": "ORGANIZE_MEMORY",
      "priority": 5
    }
  }
}
```

### 接收事件

| 事件 | 数据格式 | 说明 |
|------|----------|------|
| `agent.message` | `{"msgId":"xxx","body":{"data":{"agentId":"xxx","message":{}}}}` | 服务端推送的 Agent 消息 |

### 错误响应

当服务端处于关闭状态或发生错误时，返回：

```json
{"msgId":"","body":{"error":"错误信息"}}
```

常见错误：
- 服务关闭：`{"msgId":"","body":{"error":"server is shutting down"}}`
- 未知事件（直接由 transport 返回）：`{"error":"unknown event: xxx"}`