package me.karboom.java.iSerf.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class JobUtilTest {

    @Test
    void testAddCron() {
        var jobUtil = new JobUtil();
        var executedCount = new AtomicInteger(0);
        
        jobUtil.addPlan((v) -> {
            executedCount.incrementAndGet();
            return null;
        }, "0/2 * * * * ?", null);
        
        Assertions.assertEquals(1, jobUtil.getPlans().size());
    }

    @Test
    void testAddTime() throws InterruptedException {
        var jobUtil = new JobUtil();
        var executedCount = new AtomicInteger(0);
        var latch = new CountDownLatch(1);
        
        var time = LocalDateTime.now().plusSeconds(2);
        var timeString = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        jobUtil.addPlan((v) -> {
            executedCount.incrementAndGet();
            latch.countDown();
            return null;
        }, null, timeString);
        
        jobUtil.start();
        
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, executedCount.get());
        
        jobUtil.stop();
    }

    @Test
    void testDeletePlan() {
        var jobUtil = new JobUtil();
        var planId = "";
        
        jobUtil.addPlan((v) -> null, "0/2 * * * * ?", null);
        planId = jobUtil.getPlans().get(0).id;
        
        jobUtil.deletePlan(planId);
        Assertions.assertEquals(0, jobUtil.getPlans().size());
    }

    @Test
    void testStartStop() throws InterruptedException {
        var jobUtil = new JobUtil();
        
        jobUtil.start();
        Assertions.assertTrue(jobUtil.getIsRunning());
        
        Thread.sleep(100);
        jobUtil.stop();
        Assertions.assertFalse(jobUtil.getIsRunning());
    }


    @Test
    void testSecondLevelSchedule() throws InterruptedException {
        var jobUtil = new JobUtil();
        var executedCount = new AtomicInteger(0);
        var latch = new CountDownLatch(2);
        
        jobUtil.addPlan((v) -> {
            executedCount.incrementAndGet();
            if (executedCount.get() >= 2) {
                latch.countDown();
            }
            return null;
        }, "0/1 * * * * ?", null);
        
        jobUtil.start();
        
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(executedCount.get() >= 2);
        
        jobUtil.stop();
    }

    @Test
    void testExceptionHandling() throws InterruptedException {
        var jobUtil = new JobUtil();
        var latch = new CountDownLatch(1);
        
        jobUtil.addPlan((v) -> {
            throw new RuntimeException("测试异常");
        }, "0/1 * * * * ?", null);
        
        jobUtil.addPlan((v) -> {
            latch.countDown();
            return null;
        }, "0/1 * * * * ?", null);
        
        jobUtil.start();
        
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        
        jobUtil.stop();
    }
}
