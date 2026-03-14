package me.karboom.java.iSerf.schedule;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Slf4j
class SchedulePerformanceTest {

    @Test
    void testPerformance() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            var schedule = new Schedule();
            var planCount = 10000;
            var executionTimes = new ConcurrentLinkedQueue<Integer>();
            
            // 添加大量计划
            for (int i = 0; i < planCount; i++) {
                var planIndex = i;
                var plan = Plan.builder()
                        .id("perf-plan-" + i)
                        .description("Performance Test Plan " + i)
                        .cron("*/1 * * * * ?")
                        .functionName("perfFunction")
                        .functionParams(Map.of("index", planIndex))
                        .function((context, params) -> {
                            executionTimes.add((int) (System.currentTimeMillis() / 1000));
                        })
                        .build();
                schedule.addPlan(plan);
            }
            
            log.debug("testPerformance: Added {} plans", planCount);
            
            var startTime = System.currentTimeMillis();
            var startDateTime = LocalDateTime.now();
            
            schedule.start();
            
            log.debug("testPerformance: Schedule started at {}", startDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
            
            // 等待一段时间让计划执行
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            schedule.stop();
            
            var endTime = System.currentTimeMillis();
            var duration = endTime - startTime;
            
            // 统计每个秒数出现的次数
            var secondCounts = new HashMap<Integer, Integer>();
            for (var second : executionTimes) {
                secondCounts.merge(second, 1, Integer::sum);
            }
            
            log.debug("testPerformance: Duration: {}ms, Plans: {}", 
                    duration, planCount);
            log.debug("testPerformance: Unique seconds: {}", secondCounts.size());
            
            // 验证每个秒数出现的次数是否等于 planCount
            var invalidSeconds = new ArrayList<String>();
            secondCounts.forEach((second, count) -> {
                if (count != planCount) {
                    invalidSeconds.add("second=%d, count=%d (expected %d)".formatted(second, count, planCount));
                }
            });
            
            if (invalidSeconds.isEmpty()) {
                log.debug("testPerformance: All seconds have exactly {} executions (correct)", planCount);
            } else {
                log.debug("testPerformance: Invalid seconds found:");
                invalidSeconds.forEach(s -> log.debug("  {}", s));
            }
            
            // 输出每个秒数的执行情况
            log.debug("testPerformance: Execution count per second:");
            secondCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> 
                        log.debug("  second {}: {} executions", entry.getKey(), entry.getValue())
                    );
            
            log.info("testPerformance: Performance result - {} plans processed in {}ms", 
                    planCount, duration);
        });
    }
}