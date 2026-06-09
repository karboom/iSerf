# iSerf - 事件流驱动的智能Agent框架

iSerf 是一个基于事件流构建的智能Agent框架，采用响应式编程模型，支持创建高交互性的应用系统。该框架设计用于支持大规模Agent协作，并利用Reactor模式提供高性能的数据处理能力。

## 核心特性

- **事件流驱动**: 基于响应式编程模型，任意节点都可以监听和响应数据流
- **高交互性**: 支持创建具有复杂交互逻辑的应用程序
- **大规模支持**: 设计用于支持大量Agent协同工作
- **高性能**: 利用Reactor模式实现高效异步处理
- **多协议支持**: 内置多种通信协议实现
- **自我进化**: iFunction支持运行时自主进化代码

## 核心组件

- **Agent**: 智能代理实体，可执行各种任务
- **Team**: Agent协作团队，支持多Agent协同工作
- **LLM集成**: 支持多种大语言模型(text, audio, embedding)
- **内存管理**: 提供本地内存和其他存储机制
- **工具系统**: 支持自定义工具和功能扩展
- **服务器模块**: 包含WebSocket、WebTransport、MQTT等多种通信协议

## 安装

### 依赖配置

```kotlin
implementation("io.github.karboom:iSerf:0.6.0-alpha")
```

## 使用示例

### Agent

```java
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.llm.text.OpenAI;
import me.karboom.java.iSerf.agent.Message;

import java.util.HashMap;
import java.util.Map;

public class AgentExample {
    public static void main(String[] args) {
        // 配置LLM
        var llmConfig = new HashMap<String, Object>();
        llmConfig.put("temperature", 0.7);
        llmConfig.put("max_tokens", 2000);
        
        var llm = new OpenAI(
            "gpt-4o-mini",
            llmConfig,
            "your-api-key",
            "https://api.openai.com/v1",
            3
        );
        
        // 创建Agent
        var prompt = "你是一个智能助手。";
        var agent = new Agent("assistant", prompt, llm, null) {};
        
        // 订阅响应流
        agent.broadcast
            .doOnError(e -> {
                System.err.println("发生错误: " + e.getMessage());
            })
            .subscribe(item -> {
                System.out.println("收到响应: " + item.getText());
                System.out.println("类型: " + item.getType());
                System.out.println("是否片段: " + item.getIsSegment());
            });
        
        // 发送消息
        agent.send("你好，请介绍一下自己");
    }
}
```

### Team
```java

```


## 组件说明
### llm
目录是按照大模型的输出内容来进行划分，又根据输入/输出内容的格式进行文件夹内划分

#### 指定响应格式


### Agent
#### 记忆管理
#### 工具调用

iSerf 提供了灵活的工具调用系统，支持多种工具类型和集成方式。

**工具类型**

- **FUNCTION**：本地函数
  - 通过 `FunctionWrapper` 接口定义
  - 直接调用本地方法

- **IFUNCTION**：自我进化的iFunction
  - 支持运行时代码自主进化
  - 基于错误反馈自动修复代码
  - 采用目录结构管理多版本

- **MCP-HTTP**：HTTP协议的MCP工具
  - 通过HTTP接口集成MCP服务
  - 支持自定义请求头

- **MCP-CLI**：命令行的MCP工具
  - 通过命令行工具集成MCP服务
  - 支持环境变量配置

**工具加载**

使用 `Loader` 类加载工具：

```java
var loader = new Loader(30); // 设置30秒超时

// 从iFunction目录加载
var tools = loader.fromIFunction("path/to/iFunction", "toolName", "version");

// 从MCP CLI加载
var tools = loader.fromMCPCli("mcp-command", args);

// 从MCP HTTP加载
var tools = loader.fromMCPHttp("https://api.example.com", headers);
```

**工具调用流程**

1. LLM生成工具调用请求
2. Agent根据工具类型执行对应调用
3. 解析工具返回结果
4. 根据结果类型（direct/error/llm）进行处理

**自我进化**

iFunction支持运行时自我进化：
- 当工具调用返回error时触发
- 自动通过LLM分析错误并修复代码
- 支持重试机制，最大重试次数由 `maxEvoRetry` 控制

**错误处理方法**

工具调用返回以下格式：

```json
{
    "direct": {},
    "error": "",
    "llm": ""
}
```

**结果类型处理**：
- **direct**：直接返回给用户
- **error**：触发自我进化，修复后重试
- **llm**：返回给LLM继续处理

**自我进化流程**：
1. 捕获工具调用错误
2. 收集输入参数和错误信息
3. 调用LLM生成修复后的代码
4. 编译、加载并测试修复后的代码
5. 成功则继续，失败则重试（最多 `maxEvoRetry` 次）



#### 错误处理方法
#### 线程资源分配
### Team
#### 任务管理

---
### 服务集群

#### Websocket 集群
提供一个弹性伸缩的集群服务，每个节点是对等的，即提供服务，也充当路由。扩缩容无需迁移节点内容。外层可以增加负载均衡来统一入口。

![Websocket集群架构](https://karboom-blog.oss-cn-hangzhou.aliyuncs.com/iSerf/%E6%9E%B6%E6%9E%84%E5%9B%BE-%E6%9C%8D%E5%8A%A1%E9%9B%86%E7%BE%A4.webp)

```java

```

评测

负载均衡配置

- K8S

负载均衡使用headless service服务域名，通过dns解析获取nodes

- ECS 

直接将ip池所有地址写入Nginx，直接将ip池数组写入nodes

## 大模型工具
### 文件系统
### 数学计算
### SRAG
