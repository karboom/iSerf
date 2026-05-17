package me.karboom.java.iSerf.agent;

import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.llmProvider.FixedLlmProvider;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.persistence.IPersistence;
import me.karboom.java.iSerf.agent.persistence.NonePersistence;
import me.karboom.java.iSerf.agent.tool.*;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.billing.Cost;
import org.openjdk.jol.info.GraphLayout;
import me.karboom.java.iSerf.config.Config;
import me.karboom.java.iSerf.llm.text.IText;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.schedule.ISchedule;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.util.CodeUtil;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.HttpUtil;
import me.karboom.java.iSerf.util.JSONUtil;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
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
@Slf4j
public class Agent {
    // region ========== 元数据 ==========
    public String id;
    public String orgId;
    public String userId;

    // endregion


    public List<Message> memory = new ArrayList<>();
    protected List<Tool<?>> tools;
    protected ILlmProvider llmProvider;
    public String prompt;
    protected PriorityBlockingQueue<Event> queue;
    public Flux<Message> itemBroadcast;

    protected Sinks.Many<Message> sink;
    public Flux<Message> broadcast;

    public Integer maxEvoRetry = 5;

    private ExecutorService eventPool;
    private ExecutorService broadcastPool;

    public IPersistence persistence;

    public List<Plan> plans;
    public ISchedule schedule;

    public ILedger ledger = Config.getInstance().getDefaultLedger();

    /**
     * 工具调用缓存
     */
    public List<CallCache> toolCallCaches = new ArrayList<>();

    /**
     * 构造函数
     *
     * @param id    Agent ID
     * @param llm   LLM 实例
     * @param tools 工具列表
     */
    public Agent(String id, String prompt, ILlmProvider llm, List<Tool<?>> tools) {
        this.id = id;
        this.prompt = prompt;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.queue = new PriorityBlockingQueue<>(100, Comparator.comparing(Event::getPriority));

        this.memory.add(Message.builder().id(id).role(Message.ROLE.SYSTEM).text(this.prompt).type(Message.TYPE.TEXT).isForgotten(0).eventId("0").build());

        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.broadcast = sink.asFlux();

        this.eventPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Event-", 0).factory());
        this.broadcastPool =  Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Broadcast-", 0).factory());

        this.persistence = new NonePersistence();
        this.llmProvider = llm;

        run();
        this.recovery();
    }

    public Agent(String id, String prompt, IText llm, List<Tool<?>> tools) {
        this(id, prompt, new FixedLlmProvider(llm), tools);
    }

    public Agent(String id, ILlmProvider provider, Path path) throws IOException {
        var tools = new Loader(2000).fromToolDir(path.resolve("tool"), null);
        this(id, path, provider, tools);
    }
    /**
     * 支持自定义文件夹初始化
     * path/system-prompt.md 系统提示词
     */
    public Agent(String id, Path path, IText llm, List<Tool<?>> tools) throws IOException {
        this(id, Files.readString(path.resolve("system-prompt.md"), StandardCharsets.UTF_8), llm, tools);
    }

    public Agent(String id, Path path, ILlmProvider llm, List<Tool<?>> tools) throws IOException {
        this(id, Files.readString(path.resolve("system-prompt.md"), StandardCharsets.UTF_8), llm, tools);
    }



    public String eventTransId;
    public Disposable eventDisposable;


    // region ========== 外部调用 ==========
    /**
     * 取消当前的操作
     */
    public void interrupt() {
        this.stop();
        this.run();
    }

    /**
     * 停止运行
     */
    public void stop() {
        eventDisposable.dispose();
    }

    /**
     * 开始运行
     */
    private void run() {
        eventDisposable = Schedulers.fromExecutor(eventPool).schedule(() -> {
            while (true) {

                Event event = null;
                try {
                    event = queue.take();

                    switch (event.getType()) {
                        case Event.Type.MESSAGE -> {
                            handleMessage(event);
                        }

                        case Event.Type.ORGANIZE_MEMORY -> {
                            handleOrganizeMemory(event);
                        }

                        case Event.Type.RECOVERY -> {
                            handleRecovery(event);
                        }
                    }

                } catch (Exception e) {
                    // 不管发生了啥错误，先回滚
                    if (event != null) {

                        var eventId = event.getId();
                        switch (event.getType()) {
                            case Event.Type.MESSAGE -> {
                                // 清理中间状态的 memory
                                memory.removeIf(msg -> eventId.equals(msg.getEventId()));
                            }
                        }
                    }

                    // 不管发生了啥，一并通知上层, Todo 避免EmitError，它会终结整个流
//                    sink.tryEmitError(e);


                    // 如果是人工触发中断，停止循环
                    if (e instanceof InterruptedException) {
                        break;
                    }
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
//                                        var directCalls = new ArrayList<Communication.ToolCall>();
//                                        var errorCalls = new ArrayList<Communication.ToolCall>();
//                                        var llmCalls = new ArrayList<Communication.ToolCall>();
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
//                                            for (Communication.ToolCall call : directCalls) {
//                                                sink.tryEmitNext(Communication.builder().text(JSONUtil.stringify(call.getResult().getDirect())).isSegment(0).build());
//                                            }
//                                        }
//
//                                        // 处理ERROR类型
//                                        if (!errorCalls.isEmpty()) {
//                                            sink.tryEmitNext(Communication.builder().text("我正在更新代码，请您稍后").build());
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
//                                            var messageInvoke = Communication.builder()
//                                                    .role(Communication.ROLE.ASSISTANT)
//                                                    .toolCalls(llmCalls)
//                                                    .build();
//
//                                            var messageRes = Communication.builder()
//                                                    .role(Communication.ROLE.TOOL)
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
//                    .reduce(Communication.builder().build(), (acc, chunk) -> {
//
//                        var text = ((ChatCompletionChunk) chunk).choices().get(0).delta().content().get();
//                        if (format == null) {
//                            sink.tryEmitNext(Communication.builder().text(text).isSegment(1).build());
//                        }
//                        acc.setText(acc.getText() + text);
//
//                        var usage = ((ChatCompletionChunk) chunk).usage();
//                        usage.ifPresent(completionUsage -> message.setUsage(((int) completionUsage.totalTokens())));
//
//                        return acc;
//                    })
//                    .map(f -> {
//                        f.setIsSegment(0);
//                        f.setRole(Communication.ROLE.ASSISTANT);
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

    public void recovery () {
        queue.offer(Event.builder().id(DataUtil.getFlakeId()).type(Event.Type.RECOVERY).build());
    }


    @SneakyThrows
    public void send(String message, Class<?> cls) {
        var eventId = DataUtil.getFlakeId();
        var item = Message
                .builder()
                .type(Message.TYPE.TEXT)
                .role(Message.ROLE.USER)
                .id("")
                .isForgotten(0)
                .text(message).build();

        if (cls != null) {
            item.setFormatted(cls.getConstructors()[0].newInstance());
        }

        queue.offer(Event.builder()
                .id(eventId)
                .priority(1)
                .type(Event.Type.MESSAGE)
                .message(item)
                .build());
    }

    public void send(String message) {
        send(message, null);
    }

    // Todo 支持3个入参的版本
    public Disposable subscribe(Consumer<Message> consumer) {
        return broadcast.subscribe(consumer);
    }

    private Mono<Tuple3<Message, List<Output>, Message>> fluxHandle(Flux<Output> flux, Class format, Event event) {
        return flux
                .publishOn(Schedulers.fromExecutor(this.eventPool))
                .reduce(Tuples.of(
                        Message.builder().type(Message.TYPE.THINKING).text("").isSegment(0).build(),
                        new ArrayList<>(),
                        Message.builder().role(Message.ROLE.ASSISTANT).type(Message.TYPE.TEXT).text("").isSegment(0).eventId(event.getId()).build()
                ), (acc, chunk) -> {
                    var thinkingItem = acc.getT1();
                    var toolCall = acc.getT2();
                    var contentItem = acc.getT3();

                    var usage = chunk.getUsage();
                    var choices = chunk.getChoices();

                    if (choices != null) {
                        var delta = choices.get(0);

                        var toolCalls = delta.getToolCall();
                        var thinking = delta.getThinking();
                        var content = delta.getText();

                        if (thinking != null && !thinking.toString().equals("null")) {
                            thinkingItem.setId(UUID.randomUUID().toString()); // 为thinkingItem设置ID
                            thinkingItem.setText(thinkingItem.getText() + thinking);

                            var thinkingSegment = Message.builder().text(thinking.toString()).isSegment(1).build();

                            sink.tryEmitNext(thinkingSegment);
                        } else if (toolCalls != null) {
                            // Todo toolcall返回不及预期的时候，有可能先出问题再出toolCall
                            // Todo toolcall可能被循环触发
                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                            }
                            // 将当前chunk添加到toolCall列表中用于后续处理
                            toolCall.add(chunk);
                        } else if (content != null) {

                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                            }

                            var contentText = content;

                            contentItem.setId(UUID.randomUUID().toString());
                            contentItem.setText(contentItem.getText() + contentText);


                            if (format == null) {
                                var contentItemSegment = Message.builder().id(UUID.randomUUID().toString()).role(contentItem.getRole()).type(Message.TYPE.TEXT).text(contentText).isSegment(1).build();
                                sink.tryEmitNext(contentItemSegment);
                            }
                        } else {
                            // 其他情况，可能需要处理其他类型的响应
                            // 目前暂不处理
                        }

                    } else if (usage != null) {
                        if (contentItem.getId() != null) {
                            var usageBuilder = Message.Usage.builder()
                                    .total((int) usage.getTotalTokens())
                                    .promptTotal((int) usage.getPromptTokens())
                                    .completionTotal((int) usage.getCompletionTokens())
                                    .completionThinking(usage.getThinkingTokens())
                                    .build();

                            contentItem.setUsage(usageBuilder);

                            if (format != null) {
                                contentItem.setFormatted(JSONUtil.parse(contentItem.getText(), format));
                            }
                            sink.tryEmitNext(contentItem);
                            memory.add(contentItem);
                        }
                    } else {

                    }

                    return acc;
                });
    }

    // endregion



    // region ========== 资费相关 ==========

    /**
     * 计算内存账单
     * 1. 采用 jol 统计 memory 字段 bytes 数量
     * 2. 调用 ledge.record
     */
    public void calcMemoryBillings() {
        var memorySize = GraphLayout.parseInstance(this.memory).totalSize();
        var cost = Cost.builder()
                .id(DataUtil.getFlakeId())
                .targetType("agent")
                .targetId(this.id)
                .memory((int) memorySize)
                .captureTime(Instant.now())
                .build();

        // Todo 这个操作可以再开一个线程去做，加快响应时间
        ledger.record(cost);

        log.debug("calcMemoryBillings memory size: %s bytes".formatted(memorySize));
    }

    // endregion

    // region ========== 事件处理 ==========
    /**
     * 整理记忆
     * 1.调用llm.query，将当前的memory压缩
     * 2.压缩后的内容追加到记忆，并且将参与压缩的记忆标记forgotten
     * Todo 1.记忆重要程度判断  2.提示词
     * @param event
     */
    @SneakyThrows
    private void handleOrganizeMemory (Event event) {
        if (this.memory.isEmpty()) {
            return;
        }

        var summaryPrompt = Message.builder()
                .role(Message.ROLE.USER)
                .text("请将以上对话历史压缩为简洁的摘要，保留关键信息和上下文，用于后续对话参考。")
                .build();

        var messages = new ArrayList<Message>(this.memory.stream().skip(1).toList());
        messages.add(summaryPrompt);

        var result = llmProvider.get(null, null, null).query(messages, null);

        if (result != null && result.getChoices() != null && !result.getChoices().isEmpty()) {
            var summaryText = result.getChoices().get(0).getText();
            if (summaryText != null && !summaryText.isEmpty()) {
                var summaryItem = Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(Message.ROLE.ASSISTANT)
                        .type(Message.TYPE.TEXT)
                        .text(summaryText)
                        .isForgotten(0)
                        .eventId(event.getId())
                        .build();

                this.memory.add(summaryItem);

                this.memory.stream().skip(1).forEach(item -> {
                    if (item.getIsForgotten() == null || item.getIsForgotten() == 0) {
                        item.setIsForgotten(1);
                    }
                });

                log.debug("handleOrganizeMemory Memory compressed successfully");
            }
        }
    }

    /**
     * 处理信息输入
     * @param event
     */
    private void handleMessage(Event event) {
        var userMessage = event.getMessage();
        userMessage.setEventId(event.getId());

        // 添加到记忆中
        memory.add(userMessage);

        var format = event.getMessage().getFormatted() == null ? null : event.getMessage().getFormatted().getClass();

        var flux = llmProvider.get(tools, format, null).send(this.memory.stream().filter(item -> item.getIsForgotten() == 0).toList(), format, tools);

        fluxHandle(flux, format, event)
                .flatMapMany(holder -> {
                    var toolCallHolder = holder.getT2();

                    if (!toolCallHolder.isEmpty()) {
                        var calls = mergeToolCalls(toolCallHolder);

                        // 通过cli调用MCP函数
                        var callResult = invokeToolCalls(calls.getFirst());

                        // 按结果类型分组处理
                        var directCalls = new ArrayList<Message.ToolCall>();
                        var errorCalls = new ArrayList<Message.ToolCall>();
                        var llmCalls = new ArrayList<Message.ToolCall>();

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

                            for (Message.ToolCall call : directCalls) {
                                // 缓存工具调用参数，结果不管
                                var cache = CallCache.builder()
                                        .callId(call.getId())
                                        .toolName(call.getName())
                                        .params(call.getArguments())
                                        .build();
                                toolCallCaches.add(cache);

                                // 触发消息
                                sink.tryEmitNext(Message.builder().toolCalls(List.of(call)).custom(call.getResult().getDirect()).type(Message.TYPE.CUSTOM).isSegment(0).build());
                            }
                        }

                        // 处理ERROR类型
                        if (!errorCalls.isEmpty()) {
                            sink.tryEmitNext(Message.builder().type(Message.TYPE.ERROR).text("我正在更新代码，请您稍后").build());
                            // Todo 判断IFunction

                            for (var toolCall : errorCalls) {
                                updateToolWithRetry(toolCall, 1).subscribe();
                                invokeToolCalls(List.of(toolCall));
                            }
                        }

                        // 处理LLM类型
                        if (!llmCalls.isEmpty()) {
                            var messageInvoke = Message.builder()
                                    .role(Message.ROLE.ASSISTANT)
                                    .type(Message.TYPE.TEXT)
                                    .toolCalls(llmCalls)
                                    .isForgotten(0)
                                    .eventId(event.getId())
                                    .build();

                            var messageRes = Message.builder()
                                    .role(Message.ROLE.TOOL)
                                    .eventId(event.getId())
                                    .isForgotten(0)
                                    .toolCalls(llmCalls)
                                    .build();

                            memory.add(messageInvoke);
                            memory.add(messageRes);

                            // Todo 这里的 tools 参数是否可以去掉，节省 token
                            var newFlux = llmProvider.get(tools, null, null).send(memory, null, tools);

                            return fluxHandle(newFlux, format, event).thenMany(Flux.empty());
                        } else {
                            return Flux.empty();
                        }
                    } else {
                        return Flux.empty();
                    }
                })
                .blockLast()
        ;

        this.checkMemorySize();
    }

    private void handleRecovery(Event event) {
        var result = persistence.load(this.orgId, this.userId, this.id);

        var items = result.getT1();
        var events = result.getT2();

        log.debug("handleRecovery items size: %s, events size: %s".formatted(items.size(), events.size()));

        // 恢复记忆
        items.forEach(this.memory::add);

        // 重新触发未处理的事件
        events.stream()
                .filter(e -> e.getType() != Event.Type.RECOVERY)
                .forEach(queue::offer);
    }

    // endregion

    // region ========== 记忆相关 ==========

    /**
     * 检查记忆长度
     * 计算prompt消耗，如果大于150k，那么触发记忆整理事件，Todo 弄一个支持自定义的map，根据model的60%压缩
     */
    private void checkMemorySize() {

        var currentMemory = this.memory;
        var promptTotal = currentMemory.stream()
                .skip(1)
                .filter(item -> item.getUsage() != null && item.getUsage().getPromptTotal() != null)
                .mapToInt(item -> item.getUsage().getPromptTotal())
                .sum();

        if (promptTotal > 150000) {
            queue.offer(Event.builder().id(DataUtil.getFlakeId()).type(Event.Type.ORGANIZE_MEMORY).build());
        }
    }

    // endregion


    // region ========== 定时任务 ==========

    /**
     * 程序化创建定时任务
     */
    public void addPlan(String cron, LocalDateTime time, String name, Consumer<Map<String, Object>> function, Map<String, Object> params) {
        var plan = Plan.builder()
                .cron(cron)
                .time(time)
                .functionName(name)
                .functionParams(params)
                .build();

        this.plans.add(plan);
        this.schedule.addPlan(plan);
    }

    // endregion

    // region ========== 工具相关 ==========
    /**
     * 直接从缓存的参数调用工具
     * 1. 找出匹配的toolCallCache
     * 2. 调用函数，获取result.direct并且返回
     *
     * Todo 跑在哪个线程池里面
     */
    @SneakyThrows
    public ObjectNode invokeToolCallCache(String toolCallId) {
        log.debug("invokeToolCallCache toolCallId: " + toolCallId);

        // 根据 toolCallId 查找缓存
        var cache = toolCallCaches.stream()
                .filter(c -> c.callId.equals(toolCallId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool call cache not found: " + toolCallId));

        // 根据 toolName 匹配对应的 Tool
        var matchedTool = tools.stream()
                .filter(tool -> tool.getName().equals(cache.toolName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Tool not found: " + cache.toolName));

        var result = CallResult.builder().build();

        // Todo 这里可以封装一个函数专门调用iFunction
        var functionPath = getIFunctionPath(matchedTool);

        var classPath = "%s/%s/%s".formatted(functionPath.get(0), functionPath.get(1), functionPath.get(2));
        var versionDir = "%s/%s".formatted(functionPath.get(0), functionPath.get(1));

        // 判断 versionDir 下面是否有.class 文件，否则先编译
        if (!new File("%s.class".formatted(classPath)).exists()) {
            var javaFile = new File("%s.java".formatted(classPath));
            CodeUtil.compile(Files.readString(javaFile.toPath()), versionDir);
        }

        // 加载类
        var cls = (FunctionWrapper) CodeUtil.load(versionDir, functionPath.get(2));
        result = (cls.run(new Context(this), cache.params));

        return result.direct;
    }

    /**
     * 根据错误反馈更新工具内容
     * @deprecated
     */
    public Mono<Void> updateTool(Message.ToolCall toolCall) {
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

        // 创建请求
        var request = new Request.Builder()
                .url(apiUrl + "?name=" + toolName)
                .get()
                .build();

        // 异步执行 HTTP 请求并处理响应
        return Mono.fromCallable(() -> HttpUtil.getClient().newCall(request).execute())
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

                        var userMessage = Message.builder()
                                .role("user")
                                .text(prompt)
                                .build();

                        var messages = new ArrayList<Message>();
                        messages.add(userMessage);

                        // 调用LLM生成更新后的代码
                        return llmProvider.get(null, null, null).send(messages, null, null)
                                .map(chunk -> {
                                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                                        var delta = chunk.getChoices().get(0).getText();
                                        if (delta != null) {
                                            return delta;
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

                                    return Mono.fromCallable(() -> HttpUtil.getClient().newCall(updateRequest).execute())
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
    private Mono<Void> updateToolWithRetry(Message.ToolCall toolCall, Integer attempt) {
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

        var userMessage = Message.builder()
                .role(Message.ROLE.USER)
                .text(prompt)
                .build();

        // Todo 这里直接给format
        return llmProvider.get(null, null, null).send(List.of(userMessage), null, null)
                .reduce("", (acc, chunk) -> {
                    var text = "";
                    if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                        var delta = chunk.getChoices().get(0).getText();
                        if (delta != null) {
                            text = delta;
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
                            wrapper.run(new Context(this), toolCall.arguments);
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
     * 合并函数调用片段
     *
     * 1.根据首个chunk确定choice总数
     * 2.每个choice最终积累一个完整的Output.ToolCall
     * 3.将Output.Toolcall body属性解析json，转为Item.ToolCall
     *
     * @param chunks 流式响应块列表
     * @return 合并后的工具调用列表，每个choice对应一组ToolCall
     */
    private List<List<Message.ToolCall>> mergeToolCalls(List<Output> chunks) {
        if (chunks.isEmpty()) {
            return new ArrayList<>();
        }

        var result = new ArrayList<List<Message.ToolCall>>();

        var mergedToolCalls = new HashMap<Integer, Output.ToolCall>();

        for (var chunk : chunks) {
            var choices = chunk.getChoices();
            if (choices == null || choices.isEmpty()) {
                continue;
            }

            var toolCalls = choices.get(0).getToolCall();
            if (toolCalls == null) {
                continue;
            }

            for (var toolCall : toolCalls) {
                var index = toolCall.getIndex();
                if (index == null) {
                    continue;
                }

                mergedToolCalls.merge(index, toolCall, (existing, newCall) -> {
                    if (newCall.getId() != null && newCall.getId() != "") {
                        existing.setId(newCall.getId());
                    }
                    if (newCall.getName() != null && newCall.getName() != "") {
                        existing.setName(newCall.getName());
                    }
                    if (newCall.getArguments() != null) {
                        var existingArgs = existing.getArguments() == null ? "" : existing.getArguments();
                        existing.setArguments(existingArgs + newCall.getArguments());
                    }
                    return existing;
                });
            }
        }

        for (var entry : mergedToolCalls.entrySet()) {
            var toolCallsForChoice = new ArrayList<Message.ToolCall>();
            var outputToolCall = entry.getValue();

            var itemToolCall = Message.ToolCall.builder()
                    .id(outputToolCall.getId())
                    .name(outputToolCall.getName())
                    .arguments(JSONUtil.parse(outputToolCall.getArguments(), HashMap.class))
                    .build();

            toolCallsForChoice.add(itemToolCall);
            result.add(toolCallsForChoice);
        }

        return result;
    }

    /**
     * 调用函数
     * Todo 工具串行调用，和并行调用
     *
     * @param calls 工具调用列表
     * @return 带有调用结果的工具调用列表
     */
    private List<Message.ToolCall> invokeToolCalls(List<Message.ToolCall> calls) {

        for (var call : calls) {
            // 根据 name 匹配对应的 Tool
            Tool matchedTool = null;
            for (Tool tool : tools) {
                if (tool.getName().equals(call.name)) {
                    matchedTool = tool;
                    break;
                }
            }

            var result = CallResult.builder().build();

            if (matchedTool == null) {
                result.setLlm("Tool not found: " + call.name);
                continue;
            }

            try {
                switch (matchedTool.getType()) {
                    case Tool.TYPE.FUNCTION:
                        // 调用本地函数

                        result = matchedTool.getFunction().run(new Context(this), JSONUtil.convert(call.arguments, matchedTool.paramType));

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
                            result = (cls.run(new Context(this), JSONUtil.convert(call.arguments, matchedTool.paramType)));
                        } catch (Exception e) {
                            result.setError((RuntimeException) e);
                        }
                    }
                    break;

                    case Tool.TYPE.MCP_HTTP:
                        // 调用 HTTP 工具
                    {
                        result.setLlm ("{\"content\":\"HTTP tool '%s' called with args: %s\"}".formatted(matchedTool.getName(), call.arguments.toString()));

                    }
                    break;

                    case Tool.TYPE.MCP_CLI:
                        // 调用 CLI 工具
                    {

                        result.setLlm("{\"content\":\"CLI tool '%s' called with args: %s\"}".formatted(matchedTool.getName(), call.arguments.toString()));

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

    // endregion
}
