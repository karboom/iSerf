package me.karboom.java.iSerf.billing;

import me.karboom.java.iSerf.util.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutorService 测试类
 */
class ExecutorServiceTest {

    private NfsLedger ledger;
    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        // 创建临时文件用于测试
        tempFile = Files.createTempFile("executor-test-", ".jsonl");
        ledger = new NfsLedger(tempFile.toString(), NfsLedger.Format.JSONL);
        // 清空配置
        ExecutorService.usageLimits.clear();
    }

    /**
     * ExecutorService 综合测试用例
     */
    @Test
    void testExecutorService() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            System.out.println("=== ExecutorService 综合测试 ===");

            // ========== 1. 测试基本执行功能 ==========
            System.out.println("\n[1] 测试基本执行功能");
            var executor1 = new ExecutorService("test", "id1", "usage1");
            executor1.ledger = ledger;

            var latch1 = new CountDownLatch(1);
            executor1.execute(() -> {
                latch1.countDown();
            });

            assertTrue(latch1.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
            Thread.sleep(500);
            System.out.println("基本执行测试通过");

            // ========== 2. 测试虚拟线程名称 ==========
            System.out.println("\n[2] 测试虚拟线程名称");
            var executor2 = new ExecutorService("myType", "myId", "myUsage");
            executor2.ledger = ledger;

            var latch2 = new CountDownLatch(1);
            var threadName = new String[]{null};
            executor2.execute(() -> {
                threadName[0] = Thread.currentThread().getName();
                latch2.countDown();
            });

            assertTrue(latch2.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
            assertEquals("myType-myId-myUsage", threadName[0], "虚拟线程名称应符合格式");
            System.out.println("虚拟线程名称测试通过：" + threadName[0]);

            // ========== 3. 测试自定义并发限制 ==========
            System.out.println("\n[3] 测试自定义并发限制");
            ExecutorService.usageLimits.put("concurrent-usage", 2);

            var executor3a = new ExecutorService("concurrent", "id1", "usage");
            executor3a.ledger = ledger;
            var executor3b = new ExecutorService("concurrent", "id1", "usage");
            executor3b.ledger = ledger;
            var executor3c = new ExecutorService("concurrent", "id1", "usage");
            executor3c.ledger = ledger;

            var concurrentCount = new AtomicInteger(0);
            var maxConcurrent = new AtomicInteger(0);
            var latch3 = new CountDownLatch(3);

            Runnable concurrentTask = () -> {
                var current = concurrentCount.incrementAndGet();
                maxConcurrent.set(Math.max(maxConcurrent.get(), current));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentCount.decrementAndGet();
                latch3.countDown();
            };

            executor3a.execute(concurrentTask);
            executor3b.execute(concurrentTask);
            executor3c.execute(concurrentTask);

            assertTrue(latch3.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
            assertEquals(2, maxConcurrent.get(), "最大并发数应为 2");
            System.out.println("并发限制测试通过，最大并发数：" + maxConcurrent.get());

            // ========== 4. 测试相同 type-id-usage 共享信号量 ==========
            System.out.println("\n[4] 测试相同 type-id-usage 共享信号量");
            ExecutorService.usageLimits.put("share-usage", 1);

            var executor4a = new ExecutorService("share", "id1", "usage");
            executor4a.ledger = ledger;
            var executor4b = new ExecutorService("share", "id1", "usage");
            executor4b.ledger = ledger;

            var executionOrder = new ArrayList<String>();
            var latch4 = new CountDownLatch(2);

            executor4a.execute(() -> {
                executionOrder.add("task1-start");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executionOrder.add("task1-end");
                latch4.countDown();
            });

            executor4b.execute(() -> {
                executionOrder.add("task2-start");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executionOrder.add("task2-end");
                latch4.countDown();
            });

            assertTrue(latch4.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
            var task1EndIndex = executionOrder.indexOf("task1-end");
            var task2StartIndex = executionOrder.indexOf("task2-start");
            assertTrue(task2StartIndex > task1EndIndex, "task2 应在 task1 结束后开始");
            System.out.println("共享信号量测试通过，执行顺序：" + executionOrder);

            // ========== 5. 测试不同 id 使用不同信号量 ==========
            System.out.println("\n[5] 测试不同 id 使用不同信号量");
            ExecutorService.usageLimits.put("diff-usage", 1);

            var executor5a = new ExecutorService("diff", "id1", "usage");
            executor5a.ledger = ledger;
            var executor5b = new ExecutorService("diff", "id2", "usage");
            executor5b.ledger = ledger;

            var concurrentExecutions = new AtomicInteger(0);
            var maxConcurrentExecutions = new AtomicInteger(0);
            var latch5 = new CountDownLatch(2);

            executor5a.execute(() -> {
                var current = concurrentExecutions.incrementAndGet();
                maxConcurrentExecutions.set(Math.max(maxConcurrentExecutions.get(), current));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentExecutions.decrementAndGet();
                latch5.countDown();
            });

            executor5b.execute(() -> {
                var current = concurrentExecutions.incrementAndGet();
                maxConcurrentExecutions.set(Math.max(maxConcurrentExecutions.get(), current));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentExecutions.decrementAndGet();
                latch5.countDown();
            });

            assertTrue(latch5.await(5, TimeUnit.SECONDS), "任务应在 5 秒内完成");
            assertEquals(2, maxConcurrentExecutions.get(), "不同 id 应可并发执行");
            System.out.println("不同 id 信号量测试通过，最大并发执行数：" + maxConcurrentExecutions.get());

            // ========== 6. 测试任务异常时仍能释放信号量 ==========
            System.out.println("\n[6] 测试任务异常时仍能释放信号量");
            ExecutorService.usageLimits.put("exception-usage", 1);

            var executor6 = new ExecutorService("exception", "id1", "usage");
            executor6.ledger = ledger;

            var latch6a = new CountDownLatch(1);
            var latch6b = new CountDownLatch(1);

            executor6.execute(() -> {
                latch6a.countDown();
                throw new RuntimeException("测试异常");
            });

            assertTrue(latch6a.await(5, TimeUnit.SECONDS), "第一个任务应启动");
            Thread.sleep(500);

            executor6.execute(() -> {
                latch6b.countDown();
            });

            assertTrue(latch6b.await(5, TimeUnit.SECONDS), "第二个任务应在信号量释放后执行");
            System.out.println("异常释放信号量测试通过");

            // ========== 7. 测试性能 ==========
            System.out.println("\n[7] 测试性能");
            ExecutorService.usageLimits.put("perf-usage", 10);

            var executor7 = new ExecutorService("perf", "id1", "usage");
            executor7.ledger = ledger;

            var taskCount = 100;
            var latch7 = new CountDownLatch(taskCount);

            for (int i = 0; i < taskCount; i++) {
                executor7.execute(() -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    latch7.countDown();
                });
            }

            assertTrue(latch7.await(20, TimeUnit.SECONDS), "所有任务应在 20 秒内完成");
            Thread.sleep(1000);




        });
    }
}