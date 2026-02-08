package me.karboom.java.iSlogger.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

@Slf4j
@Data
public class JobUtil {
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static public class Plan{
        public String id;
        /**
         * 执行任务的周期表达式
         */
        public String cron;

        /**
         * 执行任务的时间
         */
        public String time;

        public Function function;
    }

    private final List<Plan> plans;
    private final ExecutorService scheduler;
    private final ScheduledExecutorService checkExecutor;
    private final CronParser cronParser;
    private final DateTimeFormatter timeFormatter;
    public Boolean isRunning;
    private Future<?> checkFuture;

    public JobUtil() {
        this.plans = new ArrayList<>();

        this.scheduler = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        this.checkExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

        this.cronParser = new CronParser(com.cronutils.model.definition.CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        this.timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        this.isRunning = false;

        log.debug("<init>: JobUtil initialized");
    }

    public void addPlan(Function function, String cron, String time) {
        var plan = Plan.builder()
                .id(UUID.randomUUID().toString())
                .cron(cron)
                .time(time)
                .function(function)
                .build();
        plans.add(plan);
        log.debug("addPlan: Added plan: id={}, cron={}, time={}", plan.id, plan.cron, plan.time);
    }

    /**
     * 删除特定计划
     * @param id
     */
    public void deletePlan(String id) {
        var removed = plans.removeIf(plan -> plan.id.equals(id));
        if (removed) {
            log.debug("deletePlan: Deleted plan: id={}", id);
        }
    }

    /**
     * 启动调度器
     */
    public void start() {
        if (isRunning) {
            log.debug("start: Scheduler is already running");
            return;
        }
        isRunning = true;
        checkFuture = checkExecutor.scheduleAtFixedRate(this::executePlans, 0, 1, TimeUnit.SECONDS);

        log.debug("start: Scheduler started");
    }

    /**
     * 停止调度器
     */
    public void stop() {
        isRunning = false;
        if (checkFuture != null) {
            checkFuture.cancel(true);
        }
        scheduler.shutdown();
        checkExecutor.shutdown();
        log.debug("stop: Scheduler stopped");
    }

    /**
     * 执行当前需要执行的计划
     */
    @SneakyThrows
    private void executePlans() {
        var now = LocalDateTime.now();
        
        log.debug("executePlans: Start executing plan check: {}", now.format(timeFormatter));

        plans.stream()
                .filter(plan -> shouldExecute(plan, now))
                .forEach(plan -> scheduler.submit(() -> {
                    try {
                        log.debug("executePlans: Executing plan: id={}, type={}", plan.id, plan.cron != null ? "cron" : "time");
                        plan.function.apply(null);
                    } catch (Exception e) {
                        log.debug("executePlans: Plan execution failed: id={}, error={}", plan.id, e.getMessage());
                    }
                }));
    }

    /**
     * 判断计划是否应该执行
     */
    @SneakyThrows
    private boolean shouldExecute(Plan plan, LocalDateTime now) {
        switch (plan.cron != null ? "cron" : plan.time != null ? "time" : "none") {
            case "cron" -> {
                var cron = cronParser.parse(plan.cron);
                var executionTime = ExecutionTime.forCron(cron);
                var zonedNow = ZonedDateTime.of(now, ZoneId.systemDefault());
                var nextExecution = executionTime.nextExecution(zonedNow);
                return nextExecution.isPresent() && nextExecution.get().getSecond() == now.getSecond();
            }
            case "time" -> {
                var targetTime = LocalDateTime.parse(plan.time, timeFormatter);
                return now.getYear() == targetTime.getYear()
                        && now.getMonth() == targetTime.getMonth()
                        && now.getDayOfMonth() == targetTime.getDayOfMonth()
                        && now.getHour() == targetTime.getHour()
                        && now.getMinute() == targetTime.getMinute()
                        && now.getSecond() == targetTime.getSecond();
            }
            default -> {
                return false;
            }
        }
    }

}
