package me.karboom.java.iSerf.schedule;

import com.cronutils.model.time.ExecutionTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

import com.cronutils.model.CronType;
import com.cronutils.parser.CronParser;

@Slf4j
@Data
public class Schedule implements ISchedule{
    private final List<Plan> plans;
    private final ExecutorService scheduler;
    private final ScheduledExecutorService checkExecutor;
    private final CronParser cronParser;
    public Boolean isRunning;
    // Todo 缓存清理逻辑
    private final Map<String, LocalDateTime> nextExecutionCache;
    private Future<?> checkFuture;

    public Schedule() {
        this.plans = new ArrayList<>();

        this.scheduler = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        this.checkExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

        this.cronParser = new CronParser(com.cronutils.model.definition.CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        this.isRunning = false;
        this.nextExecutionCache = new ConcurrentHashMap<>();

        log.debug("<init>: Schedule initialized");
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
        
        log.debug("executePlans: Start executing plan check: {}", now);

        plans.stream()
                .filter(plan -> shouldExecute(plan, now))
                .forEach(plan -> scheduler.submit(() -> {
                    try {
                        log.debug("executePlans: Executing plan: id={}, type={}", plan.id, plan.cron != null ? "cron" : "time");
                        plan.function.accept(null, null);
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
        if (plan.time == null) {
            return false;
        }
        var matches = now.equals(plan.time);
        // 如果 cron 不为 null，执行后生成下一次的时间
        if (matches && plan.cron != null) {
            updateNextTime(plan, now);
        }
        return matches;
    }

    /**
     * 根据 cron 表达式更新下次执行时间
     */
    @SneakyThrows
    private void updateNextTime(Plan plan, LocalDateTime now) {
        var cacheKey = plan.cron + "_" + now;
        var nextTime = nextExecutionCache.get(cacheKey);
        
        if (nextTime == null) {
            var cron = cronParser.parse(plan.cron);
            var executionTime = ExecutionTime.forCron(cron);
            var zonedNow = ZonedDateTime.of(now, ZoneId.systemDefault());
            var nextExecution = executionTime.nextExecution(zonedNow);
            nextExecution.ifPresent(t -> {
                nextExecutionCache.put(cacheKey, t.toLocalDateTime());
                plan.setTime(t.toLocalDateTime());
            });
        } else {
            plan.setTime(nextTime);
        }
    }

    @Override
    @SneakyThrows
    public void addPlan(Plan plan) {
        // 如果 cron 不为 null，生成初始的 time
        if (plan.cron != null && plan.time == null) {
            updateNextTime(plan, LocalDateTime.now());
        }
        this.plans.add(plan);
    }

    @Override
    public void removePlan(String planId) {
        var removed = plans.removeIf(plan -> plan.id.equals(planId));
        if (removed) {
            log.debug("deletePlan: Deleted plan: planId={}", planId);
        }
    }
}