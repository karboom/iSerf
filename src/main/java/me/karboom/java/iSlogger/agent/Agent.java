package me.karboom.java.iSlogger.agent;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionChunk;
import lombok.SneakyThrows;
import me.karboom.java.iSlogger.llm.text.BaseLLM;
import me.karboom.java.iSlogger.agent.Event;
import me.karboom.java.iSlogger.memory.Item;
import me.karboom.java.iSlogger.memory.LocalMemory;
import me.karboom.java.iSlogger.memory.Memory;
import me.karboom.java.iSlogger.tool.Tool;
import me.karboom.java.iSlogger.util.CodeUtil;
import me.karboom.java.iSlogger.util.JSONUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import me.karboom.java.iSlogger.tool.FunctionWrapper;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

/**
 * Agent 基类
 * Todo 增加一个retry方法？方便直接重试上一条
 * Todo 文件读取改异步
 */
public abstract class Agent {
    public String id;
    protected Memory memory = new LocalMemory();
    protected List<Tool> tools;
    protected BaseLLM llm;
    public String prompt;
    protected PriorityBlockingQueue<Event> queue;
    public Flux<Item> itemBroadcast;

    protected Sinks.Many<Item> sink;
    public Flux<Item> broadcast;

    public Integer maxEvoRetry = 5;

    private ExecutorService eventPool;
    private ExecutorService broadcastPool;

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
        this.queue = new PriorityBlockingQueue<>(100, Comparator.comparing(Event::getPriority));

        this.memory.add(Item.builder().id(id).role(Item.ROLE.SYSTEM).text(this.prompt).build());

        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.broadcast = sink.asFlux();

        this.eventPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Event-", 0).factory());
        this.broadcastPool =  Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Broadcast-", 0).factory());


        run();
    }


    // Todo 支持3个入参的版本
    public Disposable subscribe(Consumer<Item> consumer) {
        return broadcast.subscribe(consumer);
    }

    private Mono<Tuple3<Item, List<ChatCompletionChunk>, Item>> fluxHandle(Flux<ChatCompletionChunk> flux, Class format) {
        return flux
                .publishOn(Schedulers.fromExecutor(this.eventPool))
                .reduce(Tuples.of(
                        Item.builder().type(Item.TYPE.THINKING).text("").isSegment(0).build(),
                        new ArrayList<>(),
                        Item.builder().type(Item.TYPE.TEXT).text("").isSegment(0).build()
                ), (acc, chunk) -> {
                    var thinkingItem = acc.getT1();
                    var toolCall = acc.getT2();
                    var contentItem = acc.getT3();

                    var usage = chunk.usage();
                    var choices = chunk.choices();

                    if (!choices.isEmpty()) {
                        var delta = choices.get(0).delta();

                        var toolCalls = delta.toolCalls();
                        var thinking = delta._additionalProperties().get("reasoning_content");
                        var content = delta.content();

                        if (thinking != null && !thinking.toString().equals("null")) {
                            thinkingItem.setId(UUID.randomUUID().toString()); // 为thinkingItem设置ID
                            thinkingItem.setText(thinkingItem.getText() + thinking);

                            var thinkingSegment = Item.builder().text(thinking.toString()).isSegment(1).build();

                            sink.tryEmitNext(thinkingSegment);
                        } else if (toolCalls.isPresent()) {
                            // Todo toolcall返回不及预期的时候，有可能先出问题再出toolCall
                            // Todo toolcall可能被循环触发
                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                            }
                            // 将当前chunk添加到toolCall列表中用于后续处理
                            toolCall.add(chunk);
                        } else if (content.isPresent()) {

                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                            }

                            var contentText = content.get();

                            contentItem.setId(UUID.randomUUID().toString());
                            contentItem.setText(contentItem.getText() + contentText);


                            if (format == null) {
                                var contentItemSegment = Item.builder().type(Item.TYPE.TEXT).text(contentText).isSegment(1).build();
                                sink.tryEmitNext(contentItemSegment);
                            }
                        } else {
                            // 其他情况，可能需要处理其他类型的响应
                            // 目前暂不处理
                        }

                    } else if (usage.isPresent()) {
                        if (contentItem.getId() != null) {
                            var usageData = usage.get();
                            var completionDetails = usageData.completionTokensDetails();
                            var usageBuilder = Item.Usage.builder()
                                    .total((int) usageData.totalTokens())
                                    .promptTotal((int) usageData.promptTokens())
                                    .completionTotal((int) usageData.completionTokens())
                                    .build();

                            completionDetails.ifPresent(completionTokensDetails -> {
                                completionTokensDetails.reasoningTokens().ifPresent(obj -> {
                                    usageBuilder.setCompletionThinking(Integer.valueOf(obj.toString()));
                                });
                            });
                            
                            contentItem.setUsage(usageBuilder);

                            if (format != null) {
                                contentItem.setFormatted(JSONUtil.parse(contentItem.getText(), format));
                            }
                            sink.tryEmitNext(contentItem);
                        }
                    } else {

                    }

                    return acc;
                });
    }

    private void run() {
        // Todo 何时释放
        Schedulers.fromExecutor(eventPool).schedule(() -> {
            while (true) {
                try {
                    var event = queue.take();

                    var userMessage = Item.builder()
                            .role(Item.ROLE.USER)
                            .text(event.getItem().getText())
                            .build();

                    // 添加到记忆中
                    memory.add(userMessage);

                    var format = event.getItem().getFormatted() == null ? null : event.getItem().getFormatted().getClass();

                    var flux = llm.send(memory.get(), format, tools);

                    fluxHandle(flux, format)
                            .flatMapMany(holder -> {
                                var toolCallHolder = holder.getT2();

                                if (!toolCallHolder.isEmpty()) {
                                    var calls = mergeToolCalls(toolCallHolder);

                                    // 通过cli调用MCP函数
                                    var callResult = invokeToolCalls(calls);

                                    // 按结果类型分组处理
                                    var directCalls = new ArrayList<Item.ToolCall>();
                                    var errorCalls = new ArrayList<Item.ToolCall>();
                                    var llmCalls = new ArrayList<Item.ToolCall>();

                                    for (var call : callResult) {
                                        if (call.result.getDirect() != null) {
                                            directCalls.add(call);
                                        } else if (call.result.getError() != null) {
                                            errorCalls.add(call);
                                        } else if (call.result.getLlm() != null) {
                                            llmCalls.add(call);
                                        }
                                    }


                                    // 处理DIRECT类型
                                    if (!directCalls.isEmpty()) {
                                        for (Item.ToolCall call : directCalls) {
                                            sink.tryEmitNext(Item.builder().text(JSONUtil.stringify(call.getResult().getDirect())).isSegment(0).build());
                                        }
                                    }

                                    // 处理ERROR类型
                                    if (!errorCalls.isEmpty()) {
                                        sink.tryEmitNext(Item.builder().text("我正在更新代码，请您稍后").build());
                                        // Todo 判断IFunction

                                        for (var toolCall : errorCalls) {
                                            updateToolWithRetry(toolCall, 1).subscribe();
                                            invokeToolCalls(List.of(toolCall));
                                        }
                                    }

                                    // 处理LLM类型
                                    if (!llmCalls.isEmpty()) {
                                        var messageInvoke = Item.builder()
                                                .role(Item.ROLE.ASSISTANT)
                                                .toolCalls(llmCalls)
                                                .build();

                                        var messageRes = Item.builder()
                                                .role(Item.ROLE.TOOL)
                                                .toolCalls(llmCalls)
                                                .build();

                                        memory.add(messageInvoke);
                                        memory.add(messageRes);

                                        // Todo 这里的tools参数是否可以去掉，节省token
                                        var newFlux = llm.send(memory.get(), null, tools);

                                        return fluxHandle(newFlux, format).thenMany(Flux.empty());
                                    } else {
                                        return Flux.empty();
                                    }
                                } else {
                                    return Flux.empty();
                                }
                            })
                            .blockLast()
                    ;

                } catch (InterruptedException e) {
                    sink.tryEmitError(e);
                }
            }
        });

//            llm.send(memory.get(), format, tools)
//                    .switchOnFirst((first, other) -> {
//                        var delta = first.get().choices().get(0).delta();
//                        var toolCalls = delta.toolCalls();
//                        var thinking = delta._additionalProperties().get("reasoning_content");
//
//                        if (toolCalls.isPresent()) {
//                            return other
//                                    .collectList()
//                                    .flatMapMany((list) -> {
//                                        // 合并ToolCall参数
//                                        var calls = mergeToolCalls(list);
//
//                                        // 通过cli调用MCP函数
//                                        var callResult = invokeToolCalls(calls);
//
//                                        // 按结果类型分组处理
//                                        var directCalls = new ArrayList<Item.ToolCall>();
//                                        var errorCalls = new ArrayList<Item.ToolCall>();
//                                        var llmCalls = new ArrayList<Item.ToolCall>();
//
//                                        for (var call : callResult) {
//                                            if (call.result.getDirect() != null) {
//                                                directCalls.add(call);
//                                            } else if (call.result.getError() != null) {
//                                                errorCalls.add(call);
//                                            } else if (call.result.getLlm() != null) {
//                                                llmCalls.add(call);
//                                            }
//                                        }
//
//                                        var holder = Flux.empty();
//
//                                        // 处理DIRECT类型
//                                        if (!directCalls.isEmpty()) {
//                                            for (Item.ToolCall call : directCalls) {
//                                                sink.tryEmitNext(Item.builder().text(JSONUtil.stringify(call.getResult().getDirect())).isSegment(0).build());
//                                            }
//                                        }
//
//                                        // 处理ERROR类型
//                                        if (!errorCalls.isEmpty()) {
//                                            sink.tryEmitNext(Item.builder().text("我正在更新代码，请您稍后").build());
//                                            // Todo 判断IFunction
//
//                                            for (var toolCall : errorCalls) {
//                                                updateToolLocal(toolCall);
//                                                invokeToolCalls(List.of(toolCall));
//                                            }
//                                        }
//
//                                        // 处理LLM类型
//                                        if (!llmCalls.isEmpty()) {
//                                            var messageInvoke = Item.builder()
//                                                    .role(Item.ROLE.ASSISTANT)
//                                                    .toolCalls(llmCalls)
//                                                    .build();
//
//                                            var messageRes = Item.builder()
//                                                    .role(Item.ROLE.TOOL)
//                                                    .toolCalls(llmCalls)
//                                                    .build();
//
//                                            memory.add(messageInvoke);
//                                            memory.add(messageRes);
//
//                                            // Todo 这里的tools参数是否可以去掉，节省token
//                                            return llm.send(memory.get(), null, tools);
//                                        }
//
//                                        return holder;
//                                    });
//                        } else if (thinking != null) {
//                            return other;
//                        } else {
//                            return other;
//                        }
//                    })
//                    .reduce(Item.builder().build(), (acc, chunk) -> {
//
//                        var text = ((ChatCompletionChunk) chunk).choices().get(0).delta().content().get();
//                        if (format == null) {
//                            sink.tryEmitNext(Item.builder().text(text).isSegment(1).build());
//                        }
//                        acc.setText(acc.getText() + text);
//
//                        var usage = ((ChatCompletionChunk) chunk).usage();
//                        usage.ifPresent(completionUsage -> item.setUsage(((int) completionUsage.totalTokens())));
//
//                        return acc;
//                    })
//                    .map(f -> {
//                        f.setIsSegment(0);
//                        f.setRole(Item.ROLE.ASSISTANT);
//
//                        if (format != null) {
//                            f.setFormatted(JSONUtil.parse(f.getText(), format));
//                        }
//
//                        sink.tryEmitNext(f);
//                        memory.add(f);
//
//                        return f;
//                    })
//                    .block();
    }

    @SneakyThrows
    public void send(String message, Class<?> cls) {
        var item = Item
                .builder()
                .role(Item.ROLE.USER)
                .id("")
                .text(message).build();

        if (cls != null) {
            item.setFormatted(cls.getConstructors()[0].newInstance());
        }

        queue.offer(Event.builder()
                .type(Event.Type.MESSAGE)
                .item(item)
                .build());
    }

    public void send(String message) {
        send(message, null);
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
        var request = new Request.Builder()
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

                                    var updateRequestBody = RequestBody.create(
                                            payload,
                                            MediaType.get("application/json; charset=utf-8")
                                    );

                                    var updateRequest = new Request.Builder()
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

    @SneakyThrows
    private Mono<Void> updateToolWithRetry(Item.ToolCall toolCall, Integer attempt) {
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(toolCall.getName()))
                .findFirst()
                .orElse(null);
        if (matchedTool == null) {
            throw (new RuntimeException("Tool not found: %s".formatted(toolCall.getName())));
        }

        var functionPath = getIFunctionPath(matchedTool);
        var javaFilePath = "%s/%s/%s.java".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
        var javaFile = new File(javaFilePath);

        if (!javaFile.exists()) {
            throw new RuntimeException("Java file not found: %s".formatted(javaFilePath));
        }

        var currentCode = Files.readString(javaFile.toPath(), StandardCharsets.UTF_8);

        var prompt = "请根据以下错误信息更新代码:\n\n输入参数：\n\n%s\n\n错误信息: %s\n\n当前代码:\n%s\n\n 请修改runner里面的逻辑，仅需要告诉我最终的代码，不要带markdown标记"
                .formatted(toolCall.arguments.toString(), toolCall.getResult().toString(), currentCode);

        var userMessage = Item.builder()
                .role(Item.ROLE.USER)
                .text(prompt)
                .build();

        // Todo 这里直接给format
        return llm.send(List.of(userMessage), null, null)
                .reduce("", (acc, chunk) -> {
                    var text = "";
                    if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                        var delta = chunk.choices().get(0).delta();
                        if (delta.content().isPresent()) {
                            text = delta.content().get();
                        }
                    }
                    return acc + text;
                })

                .flatMap(updatedCode -> {
                    // 尝试编译、加载和运行代码
                    try {
                        var timestamp = System.currentTimeMillis();
                        var targetDir = "%s/%s".formatted(functionPath.get(0), timestamp);

                        CodeUtil.compile(updatedCode, targetDir);
                        var obj = CodeUtil.load(targetDir, StrUtil.upperFirst(StrUtil.toCamelCase(toolCall.getName())));
                        if (obj instanceof FunctionWrapper wrapper) {
                            wrapper.run(toolCall.arguments);
                        } else {
                            throw new RuntimeException();
                        }
                        return Mono.empty();
                    } catch (Exception e) {
                        if (attempt >= this.maxEvoRetry) {
                            throw (new RuntimeException("Max attempts reached for updating tool: %s".formatted(toolCall.getName())));
                        }
                        // 如果编译、加载或运行失败，递归调用重试
                        return updateToolWithRetry(toolCall, attempt + 1);
                    }
                });
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
                        if (toolCall.id().isPresent() && !toolCall.id().get().isEmpty()) {
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
        for (var entry : argumentsMap.entrySet()) {
            var index = entry.getKey();
            var argsJson = entry.getValue().toString();
            try {
                var argsMap = JSONUtil.parse(argsJson, HashMap.class);
                toolCallsMap.get(index).arguments = argsMap;
            } catch (Exception e) {
                // 如果解析失败，保持空的 HashMap
                sink.tryEmitError(e);
            }
        }

        return new ArrayList<>(toolCallsMap.values());
    }

    /**
     * 调用函数
     * Todo 工具串行调用，和并行调用
     *
     * @param calls 工具调用列表
     * @return 带有调用结果的工具调用列表
     */
    private List<Item.ToolCall> invokeToolCalls(List<Item.ToolCall> calls) {

        for (var call : calls) {
            // 根据 name 匹配对应的 Tool
            Tool matchedTool = null;
            for (Tool tool : tools) {
                if (tool.getName().equals(call.name)) {
                    matchedTool = tool;
                    break;
                }
            }

            var result = Item.ToolCall.Result.builder().build();

            if (matchedTool == null) {
                result.setLlm("Tool not found: " + call.name);
                continue;
            }

            try {
                switch (matchedTool.getType()) {
                    case Tool.TYPE.FUNCTION:
                        // 调用本地函数

                        String functionResult = matchedTool.getFunction().run(call.arguments);
                        // 使用新的处理函数处理directResult
                        result = handleToolCallResult(functionResult);


                        break;

                    case Tool.TYPE.IFUNCTION:
                    {
                        try {
                            var functionPath = getIFunctionPath(matchedTool);

                            var classPath = "%s/%s/%s".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
                            var versionDir = "%s/%s".formatted(functionPath.get(0), functionPath.get(1));

                            // 判断 versionDir下面是否有.class文件，否则先编译
                            if (!new File("%s.class".formatted(classPath)).exists()) {
                                var javaFile = new File("%s.java".formatted(classPath));
                                CodeUtil.compile(Files.readString(javaFile.toPath()), versionDir);
                            }

                            // 加载类
                            var cls = (FunctionWrapper) CodeUtil.load(versionDir, functionPath.get(2));
                            result = handleToolCallResult(cls.run(call.arguments));
                        } catch (Exception e) {
                            result.setError("Error calling IFunction tool '" + call.name + "': " + e.getMessage());
                        }
                    }
                    break;

                    case Tool.TYPE.MCP_HTTP:
                        // 调用 HTTP 工具
                    {
                        result = handleToolCallResult("{\"content\":\"HTTP tool '%s' called with args: %s\"}".formatted(matchedTool.getName(), call.arguments.toString()));

                    }
                    break;

                    case Tool.TYPE.MCP_CLI:
                        // 调用 CLI 工具
                    {

                            result = handleToolCallResult("{\"content\":\"CLI tool '%s' called with args: %s\"}".formatted(matchedTool.getName(), call.arguments.toString()));

                    }
                    break;
                    default:
                        throw new RuntimeException("函数类型不存在");
                }
            } catch (Exception e) {
                result.setLlm("工具调用错误" + e.getMessage());
            }

            call.result = result;
        }

        return calls;
    }

    /**
     * 构建类名路径，不带后缀名，格式为 IDirectory/驼峰toolName/从info.json解析current字段/首字母大写驼峰toolName
     *
     * @return List.of(工具目录, 版本号, 类名)
     */
    @SneakyThrows
    private List<String> getIFunctionPath(Tool tool) {
        // 获取工具的目录
        var directory = tool.getIDirectory();
        // 获取工具名的驼峰形式
        var camelCaseName = StrUtil.toCamelCase(tool.getName());

        // 构建 info.json 文件路径
        var infoFilePath = "%s/%s/info.json".formatted(directory, camelCaseName);
        var infoFile = new File(infoFilePath);


        // 读取 info.json 中的 current 字段值，默认为 "current"
        var info = JSONUtil.parse("""
                {"current":"fallback"}
                """);
        if (infoFile.exists()) {
            info = JSONUtil.parse(Files.readString(infoFile.toPath()));
        }
        var currentVersion = info.get("current").asText();

        // 获取首字母大写的驼峰工具名
        var upperFirstCamelCaseName = StrUtil.upperFirst(camelCaseName);

        // 构建最终路径
        return List.of("%s/%s".formatted(directory, camelCaseName), currentVersion, upperFirstCamelCaseName);
    }

    /**
     * 处理工具调用结果
     * 尝试解析为JSON，如果失败则将字符串作为llm返回
     */
    private Item.ToolCall.Result handleToolCallResult(String resultString) {
        try {
            return JSONUtil.parse(resultString != null ? resultString : "{}", Item.ToolCall.Result.class);
        } catch (Exception e) {
            // 解析失败时，创建包含原始字符串的Result对象，将字符串设置为llm字段
            var result = Item.ToolCall.Result.builder().build();
            result.setLlm(resultString);
            return result;
        }
    }
}
