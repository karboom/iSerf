package me.karboom.java.iSerf.schedule;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import me.karboom.java.iSerf.tool.Context;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ScheduleTest {

    @Test
    void testInitialization() {
        var schedule = new Schedule();
        
        assertNotNull(schedule);
        assertNotNull(schedule.getPlans());
        assertTrue(schedule.getPlans().isEmpty());
        assertFalse(schedule.getIsRunning());
        
        log.debug("testInitialization: Schedule initialized successfully");
    }

    @Test
    void testStartAndStop() {
        var schedule = new Schedule();
        
        assertFalse(schedule.getIsRunning());
        
        schedule.start();
        assertTrue(schedule.getIsRunning());
        
        schedule.start(); // 重复启动，应该被防护
        assertTrue(schedule.getIsRunning());
        
        schedule.stop();
        assertFalse(schedule.getIsRunning());
        
        log.debug("testStartAndStop: Schedule start and stop test passed");
    }

    @Test
    void testAddAndRemovePlan() {
        var schedule = new Schedule();
        var executed = new AtomicBoolean(false);
        
        // 创建计划
        var plan = Plan.builder()
                .id("test-plan-1")
                .description("Test Plan")
                .cron("0/5 * * * * ?")
                .functionName("testFunction")
                .functionParams(Map.of("key", "value"))
                .function((context, params) -> executed.set(true))
                .build();
        
        // 添加计划
        schedule.addPlan(plan);
        assertEquals(1, schedule.getPlans().size());
        assertEquals("test-plan-1", schedule.getPlans().get(0).getId());
        
        // 删除计划
        schedule.removePlan("test-plan-1");
        assertEquals(0, schedule.getPlans().size());
        
        // 删除不存在的计划
        schedule.removePlan("non-existent-plan");
        assertEquals(0, schedule.getPlans().size());
        
        log.debug("testAddAndRemovePlan: Add and remove plan test passed");
    }

    @Test
    void testPlanExecution() {
        var schedule = new Schedule();
        var executed = new AtomicBoolean(false);
        
        var plan = Plan.builder()
                .id("test-plan-exec")
                .description("Execution Test Plan")
                .cron("0/5 * * * * ?")
                .functionName("testFunction")
                .function((context, params) -> {
                    executed.set(true);
                    log.debug("testPlanExecution: Plan function executed");
                })
                .build();
        
        schedule.addPlan(plan);
        schedule.start();
        
        // 等待计划执行
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        schedule.stop();
        
        assertTrue(executed.get(), "Plan should have been executed");
        
        log.debug("testPlanExecution: Plan execution test passed");
    }
}