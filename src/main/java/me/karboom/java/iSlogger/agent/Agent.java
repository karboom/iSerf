package me.karboom.java.iSlogger.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.chat.completions.ChatCompletionChunk;
import me.karboom.java.iSlogger.llm.text.BaseLLM;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.memory.LocalMemory;
import me.karboom.java.iSlogger.memory.Memory;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.util.JSONUtil;
import okhttp3.OkHttpClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

/**
 * Agent 基类
 */
public abstract class Agent {
    protected String id;
    protected Memory memory = new LocalMemory();
    protected List<Tool> tools;
    protected BaseLLM llm;
    protected String prompt;
    protected PriorityBlockingQueue<Item> queue;

    protected FluxSink<Item> sink;
    public Flux<Item> broadcast;

    /**
     * 构造函数
     *
     * @param id    Agent ID
     * @param llm   LLM 实例
     * @param tools 工具列表
     */
    public Agent(String id, String prompt, BaseLLM llm, List<Tool> tools) {
        this.id = id;
        this.prompt = prompt;
        this.llm = llm;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.queue = new PriorityBlockingQueue<>(100, Comparator.comparing(Item::getId));


        Flux<Item> flux = Flux.create(s -> {
            sink = s;
        });

        broadcast = flux.share();

        run();
    }


    // Todo 支持3个入参的版本
    public Disposable subscribe(Consumer<Item> consumer) {
        return broadcast.subscribe(consumer);
    }

    private void run() {
        new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    var item = queue.take();
//                    System.out.println("\n1");
                    var userMessage = Item.builder()
                            .role("user")
                            .text(item.getText())
                            .build();

                    // 添加到记忆中
                    memory.add(userMessage);

                    llm.send(memory.get(), null, tools)
                            .switchOnFirst((first, other) -> {
                                var toolCalls = first.get().choices().get(0).delta().toolCalls();

                                if (toolCalls.isPresent()) {
                                    return other
                                            .collectList()
                                            .flatMapMany((list) -> {
                                                // 合并ToolCall参数
                                                var calls = mergeToolCalls(list);

                                                // 通过cli调用MCP函数
                                                var callResult = invokeToolCalls(calls);

                                                // 判断是否需要直接更新记忆
                                                var allDirect = true;
                                                for (Item.ToolCall call : callResult) {
                                                    if (!"direct".equals(call.result.get("type").asText())) {
                                                        allDirect = false;
                                                    }
                                                }


                                                // 判断是否需要直接更新记忆
                                                var hasError = false;
                                                for (Item.ToolCall call : callResult) {
                                                    if ("error".equals(call.result.get("type").asText())) {
                                                        hasError = true;
                                                    }
                                                }


                                                if (allDirect) {
                                                    // 构建合并后的响应文本
                                                    var responseBuilder = new StringBuilder();
                                                    for (Item.ToolCall call : callResult) {
                                                        responseBuilder.append(call.result.get("content").asText());
                                                        responseBuilder.append("\n");
                                                    }
                                                    var responseText = responseBuilder.toString().trim();

                                                    sink.next(Item.builder().text(responseText).build());
                                                    // Todo 加入记忆

                                                    return Flux.empty();
                                                } else if (hasError) {
                                                    sink.next(Item.builder().text("我正在更新代码，请您稍后").build());

                                                    // 更新代码
                                                    return Flux.empty();
                                                } else {
                                                    var messageInvoke = Item.builder().role("tool").build();
                                                    var messageRes = Item.builder().build();

                                                    memory.update(messageInvoke);
                                                    memory.update(messageRes);

                                                    return llm.send(memory.get(), null, tools);
                                                }

                                            });
                                } else {
                                    return other;
                                }
                            })
                            .reduce("", (acc, chunk) -> {
                                var text = ((ChatCompletionChunk) chunk).choices().get(0).delta().content().get();
                                sink.next(Item.builder().text(text).isSegment(1).build());
                                acc += text;
                                return acc;
                            })
                            .map(f -> {
                                var message = (Item.builder().role("assistant").text(f).isSegment(0).build());

                                sink.next(message);
                                memory.update(message);

                                return f;
                            })
                            .block();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

            }
        }).start();
    }

    public void send(String message) {
        queue.offer(Item.builder().id("").text(message).build());
    }

    /**
     * 与 Agent 对话
     *
     * @param message 用户消息
     * @return 流式响应
     */
    public Flux<ChatCompletionChunk> talk(String message) {
        // 创建用户消息
        var userMessage = Item.builder()
                .role("user")
                .text(message)
                .build();

        // 添加到记忆中
        memory.add(userMessage);

        return llm.send(memory.get(), null, tools)
                .switchOnFirst((first, other) -> {
                    var toolCalls = first.get().choices().get(0).delta().toolCalls();

                    if (toolCalls.isPresent()) {
                        return other.collectList().flatMapMany((list) -> {
                            // 合并ToolCall参数
                            var calls = mergeToolCalls(list);

                            // 通过cli调用MCP函数
                            var callResult = invokeToolCalls(calls);

                            // 判断是否需要直接更新记忆
                            var allDirect = true;
                            for (Item.ToolCall call : callResult) {
                                if (!"direct".equals(call.result.get("type").asText())) {
                                    allDirect = false;
                                }
                            }


                            // 判断是否需要直接更新记忆
                            var hasError = false;
                            for (Item.ToolCall call : callResult) {
                                if (!"error".equals(call.result.get("type").asText())) {
                                    hasError = true;
                                }
                            }


                            if (allDirect) {
                                // 构建合并后的响应文本
                                var responseBuilder = new StringBuilder();
                                for (Item.ToolCall call : callResult) {
                                    responseBuilder.append(call.result.get("content").asText());
                                    responseBuilder.append("\n");
                                }
                                var responseText = responseBuilder.toString().trim();

                                // 构造完整的 ChatCompletionChunk
                                var fake = ChatCompletionChunk.builder()
                                        .id("chatcmpl-fake-" + System.currentTimeMillis())
//                                        .object("chat.completion.chunk")
                                        .created(System.currentTimeMillis() / 1000)
                                        .model(llm.llmType)
                                        .addChoice(
                                                ChatCompletionChunk.Choice.builder()
                                                        .index(0)
                                                        .delta(
                                                                ChatCompletionChunk.Choice.Delta.builder()
                                                                        .role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
                                                                        .content(responseText)
                                                                        .build()
                                                        )
                                                        .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                                                        .build()
                                        )
                                        .build();

                                // 直接返回结果
                                return Flux.just(fake);
                            } else if (hasError) {
                                // 更新代码

                                return null;
                            } else {

                                return llm.send(memory.get(), null, tools);
                            }

                        });

                    } else {
                        return other;
                    }
                });
    }

    /**
     * 根据错误反馈更新工具内容
     */
    public Mono<Void> updateTool(Item.ToolCall toolCall) {
        // 1. 根据ToolCall 匹配tool
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(toolCall.getName()))
                .findFirst()
                .orElse(null);

        if (matchedTool == null) {
            return Mono.error(new RuntimeException("Tool not found: " + toolCall.getName()));
        }

        // 2. 通过API获取代码
        var apiUrl = "http://localhost:3000/query";
        var toolName = matchedTool.getName();
        var client = new OkHttpClient();

        // 创建请求
        var request = new okhttp3.Request.Builder()
                .url(apiUrl + "?name=" + toolName)
                .get()
                .build();

        // 异步执行HTTP请求并处理响应
        return Mono.fromCallable(() -> client.newCall(request).execute())
                .flatMap(response -> {
                    if (!response.isSuccessful()) {
                        return Mono.error(new RuntimeException("Failed to query tool code: " + response.code()));
                    }

                    try {
                        var responseBody = response.body().string();
                        var currentCode = JSONUtil.parse(responseBody).get("tool").get("content").asText();

                        // 3. 调用LLM更新代码
                        var prompt = "请根据以下错误信息更新代码:\n\n输入参数：\n\n%s\n\n错误信息: %s\n\n当前代码:\n%s\n\n 请修改runner里面的逻辑，仅需要告诉我最终的代码，不要带markdown标记"
                                .formatted(toolCall.arguments.toString(), toolCall.getResult().toString(), currentCode);

                        var userMessage = Item.builder()
                                .role("user")
                                .text(prompt)
                                .build();

                        var messages = new ArrayList<Item>();
                        messages.add(userMessage);

                        // 调用LLM生成更新后的代码
                        return llm.send(messages, null, null)
                                .map(chunk -> {
                                    if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                                        var delta = chunk.choices().get(0).delta();
                                        if (delta.content().isPresent()) {
                                            return delta.content().get();
                                        }
                                    }
                                    return "";
                                })
                                .reduce(new StringBuilder(), (sb, content) -> sb.append(content))
                                .map(StringBuilder::toString)
                                .flatMap(updatedCode -> {
                                    // 4. 通过API上传更新后的代码
                                    var updateApiUrl = "http://localhost:3000/update";
                                    var encodedCode = Base64.getEncoder().encodeToString(updatedCode.getBytes(StandardCharsets.UTF_8));
                                    var payload = "{\"name\": \"%s\", \"content\": \"%s\"}"
                                            .formatted(toolName, encodedCode);

                                    var updateRequestBody = okhttp3.RequestBody.create(
                                            payload,
                                            okhttp3.MediaType.get("application/json; charset=utf-8")
                                    );

                                    var updateRequest = new okhttp3.Request.Builder()
                                            .url(updateApiUrl)
                                            .post(updateRequestBody)
                                            .build();

                                    return Mono.fromCallable(() -> client.newCall(updateRequest).execute())
                                            .flatMap(updateResponse -> {
                                                if (!updateResponse.isSuccessful()) {
                                                    return Mono.error(new RuntimeException("Failed to upload updated code: " + updateResponse.code()));
                                                } else {
                                                    return Mono.empty();
                                                }
                                            });
                                })
                                .onErrorResume(e -> Mono.error(e));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .then(); // 转换为Mono<Void>
    }

    /**
     * 合并函数调用chunk
     *
     * @param chunks 流式响应块列表
     * @return 合并后的工具调用列表
     */
    private List<Item.ToolCall> mergeToolCalls(List<ChatCompletionChunk> chunks) {
        // 使用 Map 存储每个 index 对应的 ToolCall
        var toolCallsMap = new HashMap<Integer, Item.ToolCall>();
        // 使用 StringBuilder 累积 arguments JSON 字符串
        var argumentsMap = new HashMap<Integer, StringBuilder>();

        for (var chunk : chunks) {
            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                var delta = chunk.choices().get(0).delta();
                if (delta.toolCalls().isPresent()) {
                    for (var toolCall : delta.toolCalls().get()) {
                        int index = (int) toolCall.index();

                        // 初始化 ToolCall 对象（如果不存在）
                        if (!toolCallsMap.containsKey(index)) {
                            toolCallsMap.put(index, Item.ToolCall.builder()
                                    .arguments(new HashMap<>())
                                    .build());
                        }

                        var currentToolCall = toolCallsMap.get(index);

                        // 设置 id
                        if (toolCall.id().isPresent()) {
                            currentToolCall.id = toolCall.id().get();
                        }

                        // 设置 function 信息
                        if (toolCall.function().isPresent()) {
                            var function = toolCall.function().get();
                            if (function.name().isPresent()) {
                                currentToolCall.name = function.name().get();
                            }
                            if (function.arguments().isPresent()) {
                                // 累积 arguments 字符串
                                if (!argumentsMap.containsKey(index)) {
                                    argumentsMap.put(index, new StringBuilder());
                                }
                                argumentsMap.get(index).append(function.arguments().get());
                            }
                        }
                    }
                }
            }
        }

        // 解析累积的 arguments JSON 字符串为 HashMap
        var objectMapper = new ObjectMapper();
        for (var entry : argumentsMap.entrySet()) {
            var index = entry.getKey();
            var argsJson = entry.getValue().toString();
            try {
                @SuppressWarnings("unchecked")
                var argsMap = objectMapper.readValue(argsJson, HashMap.class);
                toolCallsMap.get(index).arguments = argsMap;
            } catch (Exception e) {
                // 如果解析失败，保持空的 HashMap
                System.err.println("Failed to parse tool call arguments: " + e.getMessage());
            }
        }

        return new ArrayList<>(toolCallsMap.values());
    }

    /**
     * 调用函数
     *
     * @param calls 工具调用列表
     * @return 带有调用结果的工具调用列表
     */
    private List<Item.ToolCall> invokeToolCalls(List<Item.ToolCall> calls) {
        var objectMapper = new ObjectMapper();

        for (var call : calls) {
            // 根据 name 匹配对应的 Tool
            Tool matchedTool = null;
            for (Tool tool : tools) {
                if (tool.getName().equals(call.name)) {
                    matchedTool = tool;
                    break;
                }
            }

            ObjectNode result = objectMapper.createObjectNode();

            if (matchedTool == null) {
                result.put("type", "error");
                result.put("content", "Tool not found: " + call.name);
            } else {
                try {
                    switch (matchedTool.getType()) {
                        case "function":
                            // 调用本地函数
                            if (matchedTool.getFunction() != null) {
                                String functionResult = matchedTool.getFunction().apply(call.arguments);
                                result.put("type", "A");
                                result.put("content", functionResult != null ? functionResult : "");
                            } else {
                                result.put("type", "error");
                                result.put("content", "Function not defined for tool: " + call.name);
                            }
                            break;

                        case "mcp-http":
                            // 调用 HTTP 工具
                            result = invokeHttpTool(matchedTool, call.arguments);
                            break;

                        case "mcp-cli":
                            // 调用 CLI 工具
                            result = invokeCliTool(matchedTool, call.arguments);
                            break;

                        default:
                            result.put("type", "error");
                            result.put("content", "Unsupported tool type: " + matchedTool.getType());
                            break;
                    }
                } catch (Exception e) {
                    result.put("type", "error");
                    result.put("content", "Error calling tool '" + call.name + "': " + e.getMessage());
                }
            }

            call.result = result;
        }

        return calls;
    }

    /**
     * 调用 HTTP 工具
     *
     * @param tool      工具对象
     * @param arguments 参数
     * @return 调用结果
     */
    private ObjectNode invokeHttpTool(Tool tool, HashMap<String, Object> arguments) {
        var objectMapper = new ObjectMapper();
        var result = objectMapper.createObjectNode();

        try {
            // 这里应该实现 HTTP 工具调用逻辑
            // 由于需要与 MCP 服务器交互，实际实现会比较复杂
            // 这里提供一个简化的示例实现
            result.put("type", "direct");
            result.put("content", "HTTP tool '" + tool.getName() + "' called with args: " + arguments.toString());
        } catch (Exception e) {
            result.put("type", "error");
            result.put("content", "Error calling HTTP tool '" + tool.getName() + "': " + e.getMessage());
        }

        return result;
    }

    /**
     * 调用 CLI 工具
     *
     * @param tool      工具对象
     * @param arguments 参数
     * @return 调用结果
     */
    private ObjectNode invokeCliTool(Tool tool, HashMap<String, Object> arguments) {
        var objectMapper = new ObjectMapper();
        var result = objectMapper.createObjectNode();

        try {
            // 这里应该实现 CLI 工具调用逻辑
            // 需要构造命令行参数并执行命令
            // 这里提供一个简化的示例实现
            result.put("type", "direct");
            result.put("content", "CLI tool '" + tool.getName() + "' called with args: " + arguments.toString());
        } catch (Exception e) {
            result.put("type", "error");
            result.put("content", "Error calling CLI tool '" + tool.getName() + "': " + e.getMessage());
        }

        return result;
    }


}
