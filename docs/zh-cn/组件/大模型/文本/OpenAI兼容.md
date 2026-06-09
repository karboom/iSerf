# OpenAI兼容接口

## 总体说明

OpenAI兼容接口是一个基于OpenAI API规范实现的大模型文本处理组件，支持流式和非流式调用、多模态输入（文本、图片、音频、视频）、结构化输出、工具调用等功能。

该组件继承自 `BaseLLM` 基类，使用OkHttp的SSE（Server-Sent Events）实现流式响应，兼容OpenAI标准接口格式。

### 核心特性

- **多模态支持**：支持文本、图片、音频、视频等多种输入类型
- **流式响应**：基于SSE实现实时流式输出
- **结构化输出**：支持JSON Schema定义的输出格式
- **工具调用**：支持函数工具调用机制
- **批量处理**：支持批量任务提交和异步查询
- **思考模式**：支持大模型的思考过程输出
- **使用统计**：自动统计token使用情况

### 初始化参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| llmType | String | 是 | 模型名称，如 `gpt-4`、`qwen-plus` 等 |
| llmConfig | Map<String, Object> | 是 | 模型配置，包括 `temperature`、`max_tokens`、`top_p` 等 |
| apiKey | String | 是 | API密钥 |
| url | String | 是 | API基础URL，如 `https://api.openai.com/v1` |
| maxRetries | Integer | 否 | 最大重试次数 |

### 支持的消息角色

- **SYSTEM**: 系统提示词
- **USER**: 用户消息
- **ASSISTANT**: 助手回复
- **TOOL**: 工具执行结果

---

## 核心功能及完整示例

### 1. 基础文本对话

#### 流式调用

```java
var llmConfig = new HashMap<String, Object>();
llmConfig.put("temperature", 0.7);
llmConfig.put("max_tokens", 1000);

var llm = new OpenAI("gpt-4", llmConfig, apiKey, apiUrl, 1);

var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个乐于助人的助手。")
    .build());
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("请介绍一下人工智能的发展历程")
    .build());

var response = llm.send(messages, null, null);

var content = new StringBuilder();
response.subscribe(
    chunk -> {
        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
            content.append(chunk.getChoices().getFirst().getText());
            System.out.print(chunk.getChoices().getFirst().getText());
        }
    },
    error -> error.printStackTrace(),
    () -> System.out.println("\n完成！")
);
```

#### 非流式调用

```java
var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个乐于助人的助手。")
    .build());
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("1+1等于几？")
    .build());

var response = llm.query(messages, null);
System.out.println(response.getChoices().getFirst().getText());
```

### 2. 结构化输出

通过指定输出格式类，让大模型按指定JSON格式返回结果：

```java
class WeatherResponse {
    public String location;
    public String weather;
    public Integer temperature;
}

var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个天气预报助手。")
    .build());
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("巴黎的天气怎么样？给出一个随机温度。")
    .build());

var response = llm.send(messages, WeatherResponse.class, null);
```

### 3. 工具调用

定义工具供大模型调用：

```java
var p1 = new Tool.Parameter("location", "string", "地点", true);
var p2 = new Tool.Parameter("continent", "string", "欧洲还是亚洲", true);
var tool = Tool.<Map>builder()
    .name("query_weather")
    .description("查询天气时使用此工具")
    .parameters(List.of(p1, p2))
    .build();

var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个天气助手。")
    .build());
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("柏林和北京的天气如何？")
    .build());

var response = llm.send(messages, null, List.of(tool));

var toolCalls = new ArrayList<OutputBO.ToolCall>();
response.subscribe(
    chunk -> {
        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
            var choice = chunk.getChoices().getFirst();
            if (choice.toolCall != null) {
                toolCalls.addAll(choice.toolCall);
            }
        }
    },
    error -> error.printStackTrace(),
    () -> {
        // 处理工具调用
        for (var toolCall : toolCalls) {
            System.out.println("工具名称: " + toolCall.getName());
            System.out.println("调用参数: " + toolCall.getArguments());
        }
    }
);
```

### 4. 多模态输入

#### 图片理解

```java
var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.IMAGE)
    .images(List.of(
        "https://example.com/image.jpg"
    ))
    .text("这张图片是什么颜色的？")
    .build());

var response = llm.send(messages, null, null);
```

#### 视频理解

```java
var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.VIDEO)
    .video("https://example.com/video.mp4")
    .fps("5")
    .text("描述这个视频的内容")
    .build());

var response = llm.send(messages, null, null);
```

#### 音频理解

```java
var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.AUDIO)
    .audio("https://example.com/audio.mp3")
    .text("描述音频的内容")
    .build());

var response = llm.send(messages, null, null);
```

### 5. 批量处理

适用于大量异步任务场景：

```java
var messageBatch = new ArrayList<List<Item>>();

// 第一个请求
var messages1 = new ArrayList<Item>();
messages1.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个助手。")
    .build());
messages1.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("巴黎的天气如何？")
    .build());
messageBatch.add(messages1);

// 第二个请求
var messages2 = new ArrayList<Item>();
messages2.add(Item.builder()
    .role(Item.ROLE.SYSTEM)
    .type(Item.TYPE.TEXT)
    .text("你是一个助手。")
    .build());
messages2.add(Item.builder()
    .role(Item.ROLE.USER)
    .type(Item.TYPE.TEXT)
    .text("北京的天气如何？")
    .build());
messageBatch.add(messages2);

// 提交批量任务
var batchId = llm.batch(messageBatch, WeatherResponse.class);
System.out.println("批量任务ID: " + batchId);

// 查询任务状态
var batchTaskInfo = llm.batchTaskInfo(batchId);
while (TaskStatusBO.STATUS.DOING.equals(batchTaskInfo.getStatus())) {
    Thread.sleep(5000);
    batchTaskInfo = llm.batchTaskInfo(batchId);
}

// 获取任务结果
if (TaskStatusBO.STATUS.DONE.equals(batchTaskInfo.getStatus())) {
    var results = llm.taskResult(batchTaskInfo);
    for (var result : results) {
        System.out.println(result.getChoices().getFirst().getText());
    }
}
```

### 6. 思考模式

启用大模型的思考过程输出：

```java
var llmConfig = new HashMap<String, Object>();
llmConfig.put("thinking", true);  // 启用思考模式
var llm = new OpenAI("gpt-4", llmConfig, apiKey, apiUrl, 1);

var messages = new ArrayList<Item>();
messages.add(Item.builder()
    .role(Item.ROLE.USER)
    .text("写一首关于春天的诗")
    .build());

var response = llm.send(messages, null, null);

var thinking = new StringBuilder();
var content = new StringBuilder();
response.subscribe(
    chunk -> {
        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
            var choice = chunk.getChoices().getFirst();
            if (choice.thinking != null) {
                thinking.append(choice.thinking);
            }
            if (choice.text != null) {
                content.append(choice.text);
            }
        }
    },
    error -> error.printStackTrace(),
    () -> {
        System.out.println("思考过程:\n" + thinking);
        System.out.println("最终回答:\n" + content);
    }
);
```

### 7. 使用统计

响应中包含详细的token使用统计：

```java
var response = llm.send(messages, null, null);

response.subscribe(
    chunk -> {
        if (chunk.getUsage() != null) {
            var usage = chunk.getUsage();
            System.out.println("输入tokens: " + usage.getPromptTokens());
            System.out.println("输出tokens: " + usage.getCompletionTokens());
            System.out.println("总tokens: " + usage.getTotalTokens());
            if (usage.getThinkingTokens() != null) {
                System.out.println("思考tokens: " + usage.getThinkingTokens());
            }
        }
    },
    error -> error.printStackTrace(),
    () -> System.out.println("完成")
);
```
