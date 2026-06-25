package me.karboom.java.iSerf.agent;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.memory.MemoryManager;
import me.karboom.java.iSerf.persistence.AgentSnapshot;
import me.karboom.java.iSerf.persistence.IAgentPersistence;
import me.karboom.java.iSerf.persistence.NoneAgentPersistence;
import me.karboom.java.iSerf.agent.tool.*;
import me.karboom.java.iSerf.billing.ILedger;
import me.karboom.java.iSerf.config.Config;
import me.karboom.java.iSerf.util.*;
import org.openjdk.jol.info.GraphLayout;
import me.karboom.java.iSerf.llm.text.Output;
import me.karboom.java.iSerf.schedule.ISchedule;
import me.karboom.java.iSerf.schedule.Plan;
import me.karboom.java.iSerf.team.Team;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

/**
 * Agent 基类
 * Todo 文件读取改异步
 */
@Slf4j
public class Agent {
    public AgentMetadata metadata;

    // region =========== 成员变量 ============


    protected MemoryManager memoryManager;
    public IAgentPersistence persistence;
    protected ILlmProvider llmProvider;

    public PriorityBlockingQueue<AgentEvent> queue;
    public Flux<AgentMessage> itemBroadcast;

    protected Sinks.Many<AgentMessage> sink;
    public Flux<AgentMessage> broadcast;

    protected ExecutorService eventPool;
    protected ExecutorService broadcastPool;

    public ISchedule schedule;

    public Disposable eventDisposable;

    public ILedger ledger;

    /**
     * 工具调用处理器
     */
    public ToolHandler toolHandler;

    /**
     * 工作目录，文件系统工具以此目录为根目录进行读写
     */
    public Path workDir;

    /**
     * 所属团队，由 Team 构造时设置
     */
    public Team team;

    // endregion

    // region ============ 构造函数 ===============

    /**
     * 空构造函数，用于纯数据构造
     *
     */
    public Agent() {
        this.memoryManager = new MemoryManager(null);
    }

    /**
     * 通过配置类构造，完整初始化所有组件
     *
     * @param config Agent 配置
     */
    public Agent(AgentConfig config) {
        this.metadata = config.getMetadata();
        this.queue = new PriorityBlockingQueue<>(100, Comparator.comparing(AgentEvent::getPriority));

        this.llmProvider = config.getLlm();
        this.persistence = config.getPersistence() != null ? config.getPersistence() : new NoneAgentPersistence();
        this.memoryManager = new MemoryManager(config.getPrompt());

        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.broadcast = sink.asFlux();

        this.eventPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Event-", 0).factory());
        this.broadcastPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Broadcast-", 0).factory());
        this.ledger = config.getLedger() != null ? config.getLedger() : Config.getInstance().getDefaultLedger();

        this.workDir = config.getWorkDir();
        this.schedule = config.getSchedule();

        this.toolHandler = new ToolHandler(config.getTools(), 5);

        // 自动创建或恢复
        var isNew = this.persistence.create(this);
        if (Boolean.FALSE.equals(isNew)) {
            this.loadFromPersistence();
        }
    }

    // endregion

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
     *
     * @return
     */
    public Agent run() {
        eventDisposable = Schedulers.fromExecutor(eventPool).schedule(() -> {
            while (true) {

                AgentEvent event = null;
                long cpuStart = 0;
                try {
                    event = queue.take();

                    cpuStart = System.nanoTime();

                    this.eventInterceptor(event);
                    switch (event.getType()) {
                        case AgentEvent.Type.MESSAGE -> {
                            handleMessage(event);
                        }

                        case AgentEvent.Type.ORGANIZE_MEMORY -> {
                            memoryManager.organizeMemory(this);
                            // 记忆压缩后全量刷盘
                            persistence.syncMemory(this);
                        }

                        case AgentEvent.Type.REDO -> {
                            handleRedo(event);
                        }
                    }

                } catch (Exception e) {
                    // 不管发生了啥错误，先回滚
                    if (event != null) {
                        var eventId = event.getId();
                        switch (event.getType()) {
                            case AgentEvent.Type.MESSAGE -> memoryManager.removeByEventId(eventId);
                        }
                    }

                    // 通知下游错误，但不终结流
                    handleError(e);
                    sink.tryEmitNext(AgentMessage.builder()
                            .type(AgentMessage.TYPE.ERROR)
                            .text(e.getMessage())
                            .build());

                    // 如果是人工触发中断，停止循环
                    if (e instanceof InterruptedException) {
                        break;
                    }
                } finally {
                    persistence.syncEvent(this);
                    if (cpuStart > 0) {
                        ledger.recordCpu(this, System.nanoTime() - cpuStart);
                    }
                }
            }
        });

        return this;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public ToolHandler getToolHandler() {
        return toolHandler;
    }

    public ILlmProvider getLlmProvider() {
        return llmProvider;
    }

    public void eventInterceptor(AgentEvent event) {
    }

    /**
     * 处理异常，子类可覆盖以自定义错误转换逻辑
     * 默认记录错误日志并原样返回
     * @return 转换后的异常，用于通知下游
     */
    protected Exception handleError(Exception e) {
        log.error("handleError", e);
        return e;
    }

    /**
     * 事件统一入口，需要对字段进行校验
     */
    public void trigger(AgentEvent event) {
        if (event == null) {
            throw ErrorUtil.make("trigger event is null");
        }

        var type = event.getType();
        var validTypes = Set.of(AgentEvent.Type.ORGANIZE_MEMORY, AgentEvent.Type.MESSAGE, AgentEvent.Type.REDO);
        if (!validTypes.contains(type)) {
            throw ErrorUtil.make("trigger invalid event type: %s".formatted(type));
        }

        if (AgentEvent.Type.MESSAGE.equals(type) ) {
            if (event.getMessage() == null) {
                throw ErrorUtil.make("trigger MESSAGE event missing message field");
            }

            var message = event.getMessage();

            message.setRole(AgentMessage.ROLE.USER);
            message.setId(DataUtil.getFlakeId());
            message.setIsForgotten(0);
        }

        if (AgentEvent.Type.REDO.equals(type)) {
            if (event.getRedoEventId() == null) {
                throw ErrorUtil.make("trigger REDO event missing redoEventId field");
            }
        }

        event.setId(DataUtil.getFlakeId());

        queue.offer(event);
    }

    @SneakyThrows
    public void send(AgentMessage message, Class<?> cls) {
        if (cls != null) {
            message.setFormatted(cls.getConstructors()[0].newInstance());
        }

        this.trigger(AgentEvent.builder()
                .priority(1)
                .type(AgentEvent.Type.MESSAGE)
                .message(message)
                .build());
    }

    @SneakyThrows
    public void send(String message, Class<?> cls) {
        var item = AgentMessage
                .builder()
                .type(AgentMessage.TYPE.TEXT)
                .text(message).build();

        this.send(item, cls);
    }

    public void send(String message) {
        send(message, null);
    }

    /**
     * 重试最后一条用户消息
     * 将 REDO 事件放入队列，由 event loop 串行处理
     */
    public void retry() {
        var lastUserMessage = memoryManager.getMessagesRaw().stream()
                .filter(msg -> AgentMessage.ROLE.USER.equals(msg.getRole()))
                .reduce((first, second) -> second)
                .orElse(null);

        if (lastUserMessage == null) {
            throw ErrorUtil.make("retry no user message found");
        }

        redoFrom(lastUserMessage.getEventId());
    }

    /**
     * 从指定 eventId 开始重做
     * 删除该轮及之后的所有记忆，重新触发用户消息
     *
     * @param eventId 起始事件的 ID
     */
    public void redoFrom(String eventId) {
        this.trigger(AgentEvent.builder()
                .priority(1)
                .type(AgentEvent.Type.REDO)
                .redoEventId(eventId)
                .build());
    }

    // Todo 支持3个入参的版本
    public Disposable subscribe(Consumer<AgentMessage> consumer) {
        return broadcast.subscribe(consumer);
    }

    private Mono<Tuple3<AgentMessage, List<Output>, AgentMessage>> fluxHandle(Flux<Output> flux, Class format, AgentEvent event) {
        return flux
                .publishOn(Schedulers.fromExecutor(this.eventPool))
                .reduce(Tuples.of(
                        AgentMessage.builder().role(AgentMessage.ROLE.ASSISTANT).type(AgentMessage.TYPE.THINKING).text("").isSegment(0).build(),
                        new ArrayList<>(),
                        AgentMessage.builder().role(AgentMessage.ROLE.ASSISTANT).type(AgentMessage.TYPE.TEXT).text("").isSegment(0).isForgotten(0).eventId(event.getId()).build()
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

                            var thinkingSegment = AgentMessage.builder().role(AgentMessage.ROLE.ASSISTANT).type(AgentMessage.TYPE.THINKING).text(thinking.toString()).isSegment(1).build();

                            sink.tryEmitNext(thinkingSegment);
                        } else if (toolCalls != null) {
                            // Todo toolcall返回不及预期的时候，有可能先出问题再出toolCall
                            // Todo toolcall可能被循环触发
                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                                memoryManager.add(thinkingItem);
                                persistence.addMemory(this);
                            }
                            // 将当前chunk添加到toolCall列表中用于后续处理
                            toolCall.add(chunk);
                        } else if (content != null) {

                            if (thinkingItem.getId() != null) {
                                // thinking阶段结束，更新thinkingItem为非片段并发送
                                sink.tryEmitNext(thinkingItem);
                                memoryManager.add(thinkingItem);
                                persistence.addMemory(this);
                                thinkingItem.setId(null);
                            }

                            var contentText = content;

                            contentItem.setId(UUID.randomUUID().toString());
                            contentItem.setText(contentItem.getText() + contentText);


                            if (format == null) {
                                var contentItemSegment = AgentMessage.builder().id(UUID.randomUUID().toString()).role(contentItem.getRole()).type(AgentMessage.TYPE.TEXT).text(contentText).isSegment(1).build();
                                sink.tryEmitNext(contentItemSegment);
                            }
                        } else {
                            // 其他情况，可能需要处理其他类型的响应
                            // 目前暂不处理
                        }

                    } else if (usage != null) {
                        if (contentItem.getId() != null) {
                            var usageBuilder = AgentMessage.Usage.builder()
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
                            memoryManager.add(contentItem);
                        }
                    } else {

                    }

                    return acc;
                });
    }

    /**
     * 直接调用智能体，执行临时需求
     * - 拼接系统提示词、message
     * - 调用llm.query，然后将结果转为Message
     */
    @SneakyThrows
    public AgentMessage call(AgentMessage message, Class<?> format) {
        log.debug("call message text: %s".formatted(message.getText()));

        var systemMessage = AgentMessage.builder()
                .role(AgentMessage.ROLE.SYSTEM)
                .text(this.memoryManager.getSystemPrompt())
                .build();

        var messages = List.of(systemMessage, message);

        var result = llmProvider.get(null, format, null).query(messages, format);
        var choices = result.getChoices();
        if (choices == null || choices.isEmpty()) {
            throw ErrorUtil.make("call query result choices is empty");
        }

        var text = choices.get(0).getText();
        if (text == null || text.isBlank()) {
            throw ErrorUtil.make("call query result text is empty");
        }

        log.debug("call result text length: %s".formatted(text.length()));

        var builder = AgentMessage.builder()
                .id(UUID.randomUUID().toString())
                .role(AgentMessage.ROLE.ASSISTANT)
                .type(AgentMessage.TYPE.TEXT)
                .text(text)
                .isSegment(0);

        if (format != null) {
            builder.formatted(JSONUtil.parse(text, format));
        }

        return builder.build();
    }
    // endregion


    // region ========== 资费相关 ==========

    /**
     * 计算内存账单
     * 1. 采用 jol 统计 memory 字段 bytes 数量
     * 2. 调用 ledge.record
     */
    public void calcMemoryBillings() {
        var memorySize = GraphLayout.parseInstance(memoryManager.getMessagesRaw()).totalSize();
        ledger.recordMemory(this, memorySize);
    }

    // endregion

    // region ========== 事件处理 ==========
    /**
     * 处理信息输入
     * @param event
     */
    private void handleMessage(AgentEvent event) {
        var userMessage = event.getMessage();
        userMessage.setEventId(event.getId());

        // 添加到记忆中
        memoryManager.add(userMessage);
        // 用户消息入库后立即持久化，避免后续 assistant 消息覆盖导致 addMemory 只写入最后一条
        persistence.addMemory(this);

        var format = event.getMessage().getFormatted() == null ? null : event.getMessage().getFormatted().getClass();

        var flux = llmProvider.get(toolHandler.getTools(), format, null).send(memoryManager.getMessagesForLLM(), format, toolHandler.getTools());

        fluxHandle(flux, format, event)
                .flatMapMany(holder -> {
                    var toolCallHolder = holder.getT2();

                    if (!toolCallHolder.isEmpty()) {
                        var calls = toolHandler.merge(toolCallHolder);

                        // 通过cli调用MCP函数
                        // Todo 普通函数调用报错了，如何传导
                        var callResult = toolHandler.invoke(this, calls.getFirst());

                        // 按结果类型分组处理
                        var directCalls = new ArrayList<AgentMessage.ToolCall>();
                        var errorCalls = new ArrayList<AgentMessage.ToolCall>();
                        var llmCalls = new ArrayList<AgentMessage.ToolCall>();

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

                            for (AgentMessage.ToolCall call : directCalls) {
                                // 缓存工具调用参数，结果不管
                                var cache = CallCache.builder()
                                        .callId(call.getId())
                                        .toolName(call.getName())
                                        .params(call.getArguments())
                                        .build();
                                toolHandler.addCache(cache);

                                // 持久化新增工具调用缓存
                                persistence.addToolCall(this);

                                // 触发消息
                                sink.tryEmitNext(AgentMessage.builder().toolCalls(List.of(call)).custom(call.getResult().getDirect()).type(AgentMessage.TYPE.CUSTOM).isSegment(0).build());
                            }
                        }

                        // 处理ERROR类型
                        if (!errorCalls.isEmpty()) {
                            sink.tryEmitNext(AgentMessage.builder().type(AgentMessage.TYPE.ERROR).text("我正在更新代码，请您稍后").build());
                            // Todo 判断IFunction

                            for (var toolCall : errorCalls) {
                                toolHandler.updateWithRetry(this, toolCall, 1).subscribe();
                                toolHandler.invoke(this, List.of(toolCall));
                            }
                        }

                        // 处理LLM类型
                        if (!llmCalls.isEmpty()) {
                            var messageInvoke = AgentMessage.builder()
                                    .role(AgentMessage.ROLE.ASSISTANT)
                                    .type(AgentMessage.TYPE.TEXT)
                                    .toolCalls(llmCalls)
                                    .isForgotten(0)
                                    .eventId(event.getId())
                                    .build();

                            var messageRes = AgentMessage.builder()
                                    .role(AgentMessage.ROLE.TOOL)
                                    .eventId(event.getId())
                                    .isForgotten(0)
                                    .toolCalls(llmCalls)
                                    .build();

                            memoryManager.add(messageInvoke);
                            memoryManager.add(messageRes);

                            // Todo 这里的 tools 参数是否可以去掉，节省 token
                            var newFlux = llmProvider.get(toolHandler.getTools(), null, null).send(memoryManager.getMessagesForLLM(), null, toolHandler.getTools());

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

        // 持久化新增记忆
        persistence.addMemory(this);

        memoryManager.checkAndOrganize(this);
    }

    /**
     * 处理重做事件
     * 1. 找到目标 USER 消息
     * 2. 删除该轮及之后的所有记忆
     * 3. 全量刷盘
     * 4. 重新触发用户消息
     */
    private void handleRedo(AgentEvent event) {
        var targetEventId = event.getRedoEventId();
        var messages = memoryManager.getMessagesRaw();

        // 找到目标 USER 消息
        var targetMessage = messages.stream()
                .filter(msg -> targetEventId.equals(msg.getEventId())
                        && AgentMessage.ROLE.USER.equals(msg.getRole()))
                .findFirst()
                .orElse(null);

        if (targetMessage == null) {
            throw ErrorUtil.make("redo target event not found: %s".formatted(targetEventId));
        }

        log.debug("handleRedo targetEventId: %s".formatted(targetEventId));

        // 深拷贝原始消息
        var originalMessage = AgentMessage.builder()
                .type(targetMessage.getType())
                .text(targetMessage.getText())
                .files(targetMessage.getFiles())
                .video(targetMessage.getVideo())
                .audio(targetMessage.getAudio())
                .formatted(targetMessage.getFormatted())
                .build();

        // 删除该轮及之后的所有记忆
        memoryManager.removeFromEventId(targetEventId);

        // 全量刷盘
        persistence.syncMemory(this);

        // 重新触发
        var format = originalMessage.getFormatted() != null
                ? originalMessage.getFormatted().getClass()
                : null;
        this.send(originalMessage, format);
    }

    private void loadFromPersistence() {
        var snapshot = persistence.load(this.metadata);
        applySnapshot(snapshot);
    }

    /**
     * 从快照恢复 Agent 状态
     *
     * @param snapshot Agent 快照
     */
    public void applySnapshot(AgentSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        // 恢复元数据
        if (snapshot.getMetadata() != null) {
            this.metadata = snapshot.getMetadata();
        }

        var items = snapshot.getMemories();
        log.debug("applySnapshot items size: %s".formatted(items != null ? items.size() : 0));

        // 恢复记忆
        if (items != null && memoryManager != null) {
            memoryManager.addAll(items);
        }

        // 恢复工具调用缓存
        if (snapshot.getToolCalls() != null && toolHandler != null) {
            snapshot.getToolCalls().forEach(toolHandler::addCache);
        }

        // 恢复定时任务计划
        if (snapshot.getPlans() != null && schedule != null) {
            snapshot.getPlans().forEach(schedule::addPlan);
        }
    }

    /**
     * 导出当前 Agent 状态为快照
     *
     * @return Agent 快照
     */
    public AgentSnapshot toSnapshot() {
        var builder = AgentSnapshot.builder();

        // 导出元数据
        if (metadata != null) {
            builder.metadata(metadata);
        }

        // 导出记忆
        if (memoryManager != null) {
            builder.memories(memoryManager.getMessagesRaw());
        }

        // 导出工具调用缓存
        if (toolHandler != null) {
            builder.toolCalls(toolHandler.getCaches());
        }

        // 导出定时任务计划
        if (schedule != null) {
            builder.plans(schedule.getPlans());
        }

        return builder.build();
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

        this.schedule.addPlan(plan);

        // 持久化定时任务计划
        persistence.syncPlan(this);
    }

    // endregion

    // region ========== 工具相关 ==========
    /**
     * 直接从缓存的参数调用工具
     * @see ToolHandler#invokeByCache
     */
    public ObjectNode invokeToolCallCache(String toolCallId) {
        return toolHandler.invokeByCache(this, toolCallId);
    }

    /**
     * 根据错误反馈更新工具内容
     * @deprecated
     * @see ToolHandler#update
     */
    public Mono<Void> updateTool(AgentMessage.ToolCall toolCall) {
        return toolHandler.update(this, toolCall);
    }

    // endregion
}
