# Socket.IO 客户端

Websocket 集群协议使用 Socket.IO，客户端连接至 `/user` 命名空间进行 Agent 相关操作。

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

        // 监听连接
        socket.on('connect', () => {
            log('已连接服务器');
        });

        // 监听错误
        socket.on('connect_error', (err) => {
            log(`连接错误：${err.message}`);
        });

        // 监听消息推送
        socket.on('agent/message', (data) => {
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

        // 调用工具缓存
        function toolCall(agentId, toolCallId) {
            return new Promise((resolve) => {
                socket.emit('agent/toolCall', JSON.stringify({ agentId, toolCallId }), (response) => {
                    const res = JSON.parse(response);
                    log(`工具调用结果：${JSON.stringify(res)}`);
                    resolve(res);
                });
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

## 客户端事件

### 发送事件

| 事件 | 请求格式 | 响应格式 | 说明 |
|------|----------|----------|------|
| `agent/create` | `{"prompt":"xxx"}` | `{"agentId":"xxx"}` | 创建 Agent，在本节点直接创建 |
| `agent/send` | `{"agentId":"xxx","event":{...}}` | - | 向 Agent 发送消息，event 结构见下方说明 |
| `agent/active` | `{"agentId":"xxx"}` | `{"success":true}` | 订阅 Agent，接收其消息推送 |
| `agent/leave` | `{"agentId":"xxx"}` | `{"success":true}` | 取消订阅 Agent |
| `agent/toolCall` | `{"agentId":"xxx","toolCallId":"xxx"}` | `{...}` | 调用 Agent 工具缓存，返回工具调用结果的 ObjectNode |

#### `agent/send` event 结构

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
// 发送文本消息
{
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

// 发送带图片的消息
{
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

// 触发记忆整理
{
  "agentId": "xxx",
  "event": {
    "type": "ORGANIZE_MEMORY",
    "priority": 5
  }
}
```

### 接收事件

| 事件 | 数据格式 | 说明 |
|------|----------|------|
| `agent/message` | `{"agentId":"xxx","message":{}}` | 服务端推送的 Agent 消息 |

### 错误响应

当服务端处于关闭状态时，所有事件返回：

```json
{"error":"server is shutting down"}