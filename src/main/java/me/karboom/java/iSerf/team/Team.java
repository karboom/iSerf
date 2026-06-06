package me.karboom.java.iSerf.team;

import lombok.Data;
import lombok.NoArgsConstructor;
import me.karboom.java.iSerf.agent.Agent;
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


        // Todo 这一段是否有必要和watch合并
        var leaderBroadcast = leader.broadcast.map(item -> Message.builder().agentId(leader.id).mentions(null).message(item).build());
        var membersBroadcast = Flux.fromIterable(this.member)
                .flatMap(m -> m.broadcast.map(item -> Message.builder().agentId(m.id).message(item).build()));
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
                            .map(member -> "ID: %s, Prompt: %s".formatted(member.id, member.prompt))
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
                                // Todo Agent 在这里直接并发
                                // 如果任务状态为进行中，找出对应的agent
                                var targetAgent = this.member.stream()
                                        .filter(agent -> agent.metadata.getId().equals(task.getAgentId()))
                                        .findFirst()
                                        .orElse(null);

                                if (targetAgent != null) {
                                    // 找到了对应的agent，可以向该agent发送任务信息
                                    targetAgent.send("Task: " + task.getDesc(), null);
                                }
                            }
                        }
                    }
                }
                case Event.TYPE.MESSAGE -> {

                }
                case Event.TYPE.COMMENT -> {

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
