package me.karboom.java.iSerf.agent;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.llmProvider.ILlmProvider;
import me.karboom.java.iSerf.agent.memory.MemoryManager;
import me.karboom.java.iSerf.agent.persistence.IPersistence;
import me.karboom.java.iSerf.agent.persistence.NonePersistence;
import me.karboom.java.iSerf.agent.tool.*;
import me.karboom.java.iSerf.agent.billing.AgentBilling;
import me.karboom.java.iSerf.billing.ILedger;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public AgentMetadata metadata;

    // region =========== 成员变量 ============


    private MemoryManager memoryManager;
    public IPersistence persistence;
    protected ILlmProvider llmProvider;

    protected PriorityBlockingQueue<Event> queue;
    public Flux<Message> itemBroadcast;

    protected Sinks.Many<Message> sink;
    public Flux<Message> broadcast;

    private ExecutorService eventPool;
    private ExecutorService broadcastPool;

    public ISchedule schedule;

    public Disposable eventDisposable;

    public AgentBilling agentBilling;

    /**
     * 工具调用处理器
     */
    protected ToolHandler toolHandler;

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
     * 全参构造函数
     *
     * @param metadata    Agent 元数据（id、orgId、userId）
     * @param prompt      系统提示词
     * @param llm         LLM 实例
     * @param tools       工具列表
     * @param persistence 持久化实现，null 则使用 NonePersistence
     * @param ledger      计费账本，null 则使用 Config 默认账本
     * @param workDir     工作目录，文件系统工具以此目录为根
     * @param schedule    定时任务调度器，null 则不启用定时任务
     */
    public Agent(AgentMetadata metadata, String prompt, ILlmProvider llm, List<Tool<?>> tools,
                 IPersistence persistence, ILedger ledger, Path workDir, ISchedule schedule) {
        this.metadata = metadata;
        this.queue = new PriorityBlockingQueue<>(100, Comparator.comparing(Event::getPriority));

        this.llmProvider = llm;
        this.persistence = persistence != null ? persistence : new NonePersistence();
        this.memoryManager = new MemoryManager(prompt, llm);

        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.broadcast = sink.asFlux();

        this.eventPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Event-", 0).factory());
        this.broadcastPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("Agent-Broadcast-", 0).factory());
        this.agentBilling = new AgentBilling(metadata.getId(), ledger);

        this.workDir = workDir;
        this.schedule = schedule;

        this.toolHandler = new ToolHandler(tools, 5, llm);
    }

    /**
     * 便捷构造函数，使用默认持久化和默认账本
     */
    public Agent(String id, String prompt, ILlmProvider llm, List<Tool<?>> tools) {
        this(AgentMetadata.builder().id(id).build(), prompt, llm, tools, null, null, null, null);
    }

    /**
     * 文件系统构造函数
     * path/system-prompt.md 系统提示词
     */
    public Agent(String id, ILlmProvider provider, Path path) throws IOException {
        var tools = new Loader(2000).fromToolFile(path.resolve("tool"), null);
        this(AgentMetadata.builder().id(id).build(), Files.readString(path.resolve("system-prompt.md"), StandardCharsets.UTF_8), provider, tools,
                null, null, path, null);
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

                Event event = null;
                long cpuStart = 0;
                try {
                    event = queue.take();

                    cpuStart = System.nanoTime();

                    this.eventInterceptor(event);
                    switch (event.getType()) {
                        case Event.Type.MESSAGE -> {
                            handleMessage(event);
                        }

                        case Event.Type.ORGANIZE_MEMORY -> {
                            memoryManager.organizeMemory();
                        }

                        case Event.Type.RECOVERY -> {
                            handleRecovery(event);
                        }
                    }

                } catch (Exception e) {
                    // Todo 这里的错误如何抛出

                    // 不管发生了啥错误，先回滚
                    if (event != null) {

                        var eventId = event.getId();
                        switch (event.getType()) {
                            case Event.Type.MESSAGE -> {
                                // 清理中间状态的 memory
                                memoryManager.removeByEventId(eventId);
                            }
                        }
                    }

                    // 不管发生了啥，一并通知上层, Todo 避免EmitError，它会终结整个流
//                    sink.tryEmitError(e);


                    // 如果是人工触发中断，停止循环
                    if (e instanceof InterruptedException) {
                        break;
                    }
                } finally {
                    if (cpuStart > 0) {
                        agentBilling.recordCpu(System.nanoTime() - cpuStart);
                    }
                }
            }
        });

        this.recovery();

        return this;
    }

    public void recovery () {
        this.trigger(Event.builder().type(Event.Type.RECOVERY).build());
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public void eventInterceptor(Event event) {
    }
    /**
     * 事件统一入口，需要对字段进行校验
     */
    public void trigger(Event event) {
        if (event == null) {
            throw ErrorUtil.make("trigger event is null");
        }

        var type = event.getType();
        var validTypes = Set.of(Event.Type.ORGANIZE_MEMORY, Event.Type.MESSAGE, Event.Type.RECOVERY);
        if (!validTypes.contains(type)) {
            throw ErrorUtil.make("trigger invalid event type: %s".formatted(type));
        }

        if (Event.Type.MESSAGE.equals(type) ) {
            if (event.getMessage() == null) {
                throw ErrorUtil.make("trigger MESSAGE event missing message field");
            }

            var message = event.getMessage();

            message.setRole(Message.ROLE.USER);
            message.setId(DataUtil.getFlakeId());
            message.setIsForgotten(0);
        }

        event.setId(DataUtil.getFlakeId());

        queue.offer(event);
    }

    @SneakyThrows
    public void send(Message message, Class<?> cls) {
        if (cls != null) {
            message.setFormatted(cls.getConstructors()[0].newInstance());
        }

        this.trigger(Event.builder()
                .priority(1)
                .type(Event.Type.MESSAGE)
                .message(message)
                .build());
    }

    @SneakyThrows
    public void send(String message, Class<?> cls) {
        var item = Message
                .builder()
                .type(me.karboom.java.iSerf.agent.Message.TYPE.TEXT)
                .text(message).build();

        this.send(item, cls);
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
                        Message.builder().role(Message.ROLE.ASSISTANT).type(Message.TYPE.TEXT).text("").isSegment(0).isForgotten(0).eventId(event.getId()).build()
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

                            var thinkingSegment = me.karboom.java.iSerf.agent.Message.builder().role(Message.ROLE.ASSISTANT).type(Message.TYPE.THINKING).text(thinking.toString()).isSegment(1).build();

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
                                thinkingItem.setId(null);
                            }

                            var contentText = content;

                            contentItem.setId(UUID.randomUUID().toString());
                            contentItem.setText(contentItem.getText() + contentText);


                            if (format == null) {
                                var contentItemSegment = me.karboom.java.iSerf.agent.Message.builder().id(UUID.randomUUID().toString()).role(contentItem.getRole()).type(me.karboom.java.iSerf.agent.Message.TYPE.TEXT).text(contentText).isSegment(1).build();
                                sink.tryEmitNext(contentItemSegment);
                            }
                        } else {
                            // 其他情况，可能需要处理其他类型的响应
                            // 目前暂不处理
                        }

                    } else if (usage != null) {
                        if (contentItem.getId() != null) {
                            var usageBuilder = me.karboom.java.iSerf.agent.Message.Usage.builder()
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
    public Message call(Message message, Class<?> format) {
        log.debug("call message text: %s".formatted(message.getText()));

        var systemMessage = Message.builder()
                .role(Message.ROLE.SYSTEM)
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

        var builder = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(Message.ROLE.ASSISTANT)
                .type(Message.TYPE.TEXT)
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
        agentBilling.recordMemory(memorySize);
    }

    // endregion

    // region ========== 事件处理 ==========
    /**
     * 处理信息输入
     * @param event
     */
    private void handleMessage(Event event) {
        var userMessage = event.getMessage();
        userMessage.setEventId(event.getId());

        // 添加到记忆中
        memoryManager.add(userMessage);

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
                                toolHandler.addCache(cache);

                                // 触发消息
                                sink.tryEmitNext(me.karboom.java.iSerf.agent.Message.builder().toolCalls(List.of(call)).custom(call.getResult().getDirect()).type(me.karboom.java.iSerf.agent.Message.TYPE.CUSTOM).isSegment(0).build());
                            }
                        }

                        // 处理ERROR类型
                        if (!errorCalls.isEmpty()) {
                            sink.tryEmitNext(me.karboom.java.iSerf.agent.Message.builder().type(me.karboom.java.iSerf.agent.Message.TYPE.ERROR).text("我正在更新代码，请您稍后").build());
                            // Todo 判断IFunction

                            for (var toolCall : errorCalls) {
                                toolHandler.updateWithRetry(this, toolCall, 1).subscribe();
                                toolHandler.invoke(this, List.of(toolCall));
                            }
                        }

                        // 处理LLM类型
                        if (!llmCalls.isEmpty()) {
                            var messageInvoke = me.karboom.java.iSerf.agent.Message.builder()
                                    .role(me.karboom.java.iSerf.agent.Message.ROLE.ASSISTANT)
                                    .type(me.karboom.java.iSerf.agent.Message.TYPE.TEXT)
                                    .toolCalls(llmCalls)
                                    .isForgotten(0)
                                    .eventId(event.getId())
                                    .build();

                            var messageRes = me.karboom.java.iSerf.agent.Message.builder()
                                    .role(me.karboom.java.iSerf.agent.Message.ROLE.TOOL)
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

        memoryManager.checkAndOrganize();
    }

    private void handleRecovery(Event event) {
        var result = persistence.load(this.metadata.getOrgId(), this.metadata.getUserId(), this.metadata.getId());

        var items = result.getT1();
        var events = result.getT2();

        log.debug("handleRecovery items size: %s, events size: %s".formatted(items.size(), events.size()));

        // 恢复记忆
        memoryManager.addAll(items);

        // 重新触发未处理的事件
        events.stream()
                .filter(e -> e.getType() != Event.Type.RECOVERY)
                .forEach(queue::offer);
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
    public Mono<Void> updateTool(Message.ToolCall toolCall) {
        return toolHandler.update(toolCall);
    }

    // endregion
}
