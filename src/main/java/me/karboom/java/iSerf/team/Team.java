package me.karboom.java.iSerf.team;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.karboom.java.iSerf.agent.Agent;
import me.karboom.java.iSerf.agent.AgentMessage;
import me.karboom.java.iSerf.util.DataUtil;
import me.karboom.java.iSerf.util.ErrorUtil;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

@Slf4j
public class Team {

    @NoArgsConstructor
    @Data
    public static class SplitResult {
        public List<Task> tasks;
    }

    /**
     * 工作目录
     */
    public Path workDir;
    /**
     * 任务列表
     */
    public List<Task> tasks;


    /**
     * 智能体成员
     */
    public Agent leader;
    public List<Agent> member;


    /**
     * 事件总线发布
     */
    public PriorityBlockingQueue<Event> eventQueue;
    /**
     * 事件总线订阅
     */
    public Flux<Event> eventBroadcast;

    /**
     * 消息总线订阅
     */
    public Flux<Message> broadcast;

    public Team(Agent leader, List<Agent> members) {
        this.leader = leader;
        this.member = new ArrayList<>(members);

        // 设置团队引用，使工具可以通过 Context 访问 Team
        this.leader.team = this;
        for (var m : this.member) {
            m.team = this;
        }

        // Todo 这一段是否有必要和watch合并
        var leaderBroadcast = leader.broadcast.map(item -> Message.builder().agentId(leader.metadata.getId()).mentions(null).message(item).build());
        Flux<Message> membersBroadcast = Flux.fromIterable(this.member)
                .flatMap(m -> m.broadcast.map(item -> Message.builder().agentId(m.metadata.getId()).message(item).build()));
        this.broadcast = Flux.merge(leaderBroadcast, membersBroadcast).share();

        this.eventQueue = new PriorityBlockingQueue<>(100, Comparator.comparing(Event::getId));
        this.eventBroadcast = Flux.<Event>generate(sink -> {
            try {
                var event = eventQueue.take();
                sink.next(event);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).share();

        this.watch();
        this.run();
    }

    /**
     * 分析Agent消息总线，转换为Team事件总线
     */
    public void watch(){
        this.leader.subscribe(item -> {
            System.out.println(item.getText());

            if (item.formatted instanceof SplitResult plan) {
                // Todo 检查task逻辑漏洞（依赖关系异常、有member未分配到任务）
                this.tasks = plan.getTasks();

                for (var task : tasks) {
                    if (task.getUpstreamIds() == null || task.getUpstreamIds().isEmpty()) {
                        task.setStatus(Task.STATUS.DOING);
                        eventQueue.offer(Event.builder()
                                .id(DataUtil.getFlakeId())
                                .type(Event.TYPE.TASK_CHANGE)
                                .desc("Task " + task.getId() + " changed to doing")
                                .build());
                    }
                }
            }
        });

        for (var member: this.member) {
            // Todo 每个成员不同，逻辑如何判断
            member.subscribe(item -> {
                if (item.getIsSegment() == 0) {
                    System.out.println("member: " + item.getText());
                }
            });
        }
    }

    /**
     * 启动对事件总线的监听
     */
    public void run() {
        eventBroadcast.subscribe(event -> {
            switch (event.getType()) {
                case Event.TYPE.TARGET -> {
                    var memberInfo = this.member.stream()
                            .map(member -> "ID: %s, Prompt: %s".formatted(member.metadata.getId(), member.getMemoryManager().getSystemPrompt()))
                            .reduce("", (acc, info) -> acc + "\n" + info);

                    var enhancedMessage = event.getDesc() + "\n团队成员信息:" + memberInfo;
                    this.leader.send(enhancedMessage, SplitResult.class);
                }
                case Event.TYPE.TASK_CHANGE -> {

                    // 检查是否所有任务都已完成
                    var allDone = tasks.stream().allMatch(task -> task.getStatus().equals(Task.STATUS.DONE));
                    if (allDone) {
                        eventQueue.offer(Event.builder()
                                .id(DataUtil.getFlakeId())
                                .type(Event.TYPE.COMPLETE)
                                .desc("All tasks completed")
                                .build());
                        return;
                    }
                    
                    // 遍历任务，根据任务状态进行相应处理
                    for (var task : tasks) {
                        switch (task.getStatus()) {
                            case Task.STATUS.WAITING -> {
                                var upstreamTaskIds = task.getUpstreamIds();

                                // 检查上游任务是否全部完成
                                var upstreamTasks = tasks.stream()
                                        .filter(t -> upstreamTaskIds.contains(t.getId()))
                                        .toList();

                                var allUpstreamDone = upstreamTasks.stream()
                                        .allMatch(t -> t.getStatus().equals(Task.STATUS.DONE));

                                if (allUpstreamDone) {
                                    task.setStatus(Task.STATUS.DOING);
                                    eventQueue.offer(Event.builder()
                                            .id(DataUtil.getFlakeId())
                                            .type(Event.TYPE.TASK_CHANGE)
                                            .desc("Task " + task.getId() + " changed to doing")
                                            .build());
                                }
                            }
                            case Task.STATUS.DOING -> {
                                // 如果任务状态为进行中，找出对应的agent，开虚拟线程并发执行
                                var targetAgent = this.member.stream()
                                        .filter(agent -> agent.metadata.getId().equals(task.getAgentId()))
                                        .findFirst()
                                        .orElse(null);

                                if (targetAgent != null) {
                                    Thread.ofVirtual().name("task-" + task.getId()).start(() -> {
                                        try {
                                            var taskMessage = """
                                                    任务ID: %s
                                                    任务描述: %s
                                                    请执行上述任务并返回结果。
                                                    """.formatted(task.getId(), task.getDesc());

                                            var result = targetAgent.call(
                                                    AgentMessage.builder()
                                                            .role(AgentMessage.ROLE.USER)
                                                            .type(AgentMessage.TYPE.TEXT)
                                                            .text(taskMessage)
                                                            .build(),
                                                    null
                                            );

                                            log.info("Task {} completed by agent {}, result length: {}",
                                                    task.getId(), task.getAgentId(),
                                                    result.getText() != null ? result.getText().length() : 0);
                                        } catch (Exception e) {
                                            log.error("Task {} execution failed: {}", task.getId(), e.getMessage(), e);
                                        }
                                    });
                                }
                            }
                        }
                    }
                }
                case Event.TYPE.MESSAGE -> {

                }
                case Event.TYPE.COMMENT -> {
                    // 找到对应的任务
                    var commentTask = tasks.stream()
                            .filter(t -> t.getId().equals(event.getTaskId()))
                            .findFirst()
                            .orElse(null);

                    if (commentTask == null) {
                        log.warn("COMMENT event references unknown task: {}", event.getTaskId());
                        return;
                    }

                    // 找到任务负责人
                    var taskAgent = this.member.stream()
                            .filter(agent -> agent.metadata.getId().equals(commentTask.getAgentId()))
                            .findFirst()
                            .orElse(null);

                    if (taskAgent == null) {
                        log.warn("COMMENT event task {} has no assigned agent: {}", commentTask.getId(), commentTask.getAgentId());
                        return;
                    }

                    // 构建评论信息：整个任务上下文 + 评论内容
                    var resultInfo = commentTask.getResult() != null ? "当前结果: %s\n".formatted(commentTask.getResult()) : "";
                    var commentsInfo = (commentTask.getComments() != null && !commentTask.getComments().isEmpty())
                            ? "\n【历史评论】\n%s\n".formatted(
                                commentTask.getComments().stream()
                                    .map(c -> "- [%s] %s".formatted(
                                        c.getAgentId() != null ? c.getAgentId() : c.getUserId(),
                                        c.getContent()))
                                    .reduce("", (a, b) -> a + b))
                            : "";
                    var commentMessage = """
                            你有一个任务收到了评审意见，请根据意见进行修改。

                            【任务信息】
                            任务ID: %s
                            任务描述: %s
                            任务状态: %s
                            %s%s
                            【最新评审意见】
                            %s

                            请根据以上评审意见对你的任务结果进行修改和完善。""".formatted(
                                commentTask.getId(),
                                commentTask.getDesc(),
                                commentTask.getStatus(),
                                resultInfo,
                                commentsInfo,
                                event.getDesc());

                    // 开虚拟线程调用任务负责人
                    Thread.ofVirtual().name("comment-" + commentTask.getId()).start(() -> {
                        try {
                            var result = taskAgent.call(
                                    AgentMessage.builder()
                                            .role(AgentMessage.ROLE.USER)
                                            .type(AgentMessage.TYPE.TEXT)
                                            .text(commentMessage)
                                            .build(),
                                    null
                            );

                            log.info("Comment on task {} processed by agent {}, result length: {}",
                                    commentTask.getId(), commentTask.getAgentId(),
                                    result.getText() != null ? result.getText().length() : 0);
                        } catch (Exception e) {
                            log.error("Comment on task {} processing failed: {}", commentTask.getId(), e.getMessage(), e);
                        }
                    });
                }
                default -> {
                }
            }
        });
    }



    /**
     * 事件统一入口，需要对字段进行校验
     */
    public void trigger(Event event) {
        if (event == null) {
            throw ErrorUtil.make("trigger event is null");
        }

        var type = event.getType();
        var validTypes = Set.of(
                Event.TYPE.TARGET,
                Event.TYPE.TASK_CHANGE,
                Event.TYPE.COMPLETE,
                Event.TYPE.MESSAGE,
                Event.TYPE.COMMENT
        );
        if (!validTypes.contains(type)) {
            throw ErrorUtil.make("trigger invalid event type: %s".formatted(type));
        }

        event.setId(DataUtil.getFlakeId());

        eventQueue.offer(event);
    }

    public void send(String message) {
        trigger(Event.builder().type(Event.TYPE.TARGET).desc(message).build());
    }


    public Disposable subscribe(Consumer<Message> consumer) {
        return broadcast.subscribe(consumer);
    }

    /**
     * 修改任务状态
     */
    public void taskChangeStatus(String id, String status) {
        trigger(Event.builder().type(Event.TYPE.TASK_CHANGE).desc("%s-%s".formatted(id, status)).build());
    }


}
