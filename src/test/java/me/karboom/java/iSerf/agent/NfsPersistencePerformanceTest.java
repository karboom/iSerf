package me.karboom.java.iSerf.agent;

import me.karboom.java.iSerf.agent.persistence.NfsPersistence;
import me.karboom.java.iSerf.util.DataUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NfsPersistencePerformanceTest {

    @Test
    void loadPerformance() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);

            // 使用 generateSimulatedCborFile 生成的固定 ID 加载 200 轮对话数据
            var orgId = "test-org-simulated";
            var userId = "test-user-simulated";
            var agentId = "test-agent-simulated";

            var callCount = 10000;
            var completedCount = new AtomicInteger(0);
            var latch = new CountDownLatch(callCount);

            System.out.println("开始执行 " + callCount + " 次 load 调用...");
            var startTime = System.currentTimeMillis();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var i = 0; i < callCount; i++) {
                    executor.submit(() -> {
                        try {
                            var result = persistence.load(orgId, userId, agentId);
                            if (result.getT1().isEmpty()) {
                                System.out.println("加载结果为空");
                            }
                        } finally {
                            completedCount.incrementAndGet();
                            latch.countDown();
                        }
                    });
                }

                latch.await();
            }

            var totalTime = System.currentTimeMillis() - startTime;

            System.out.println("=== NfsPersistence Load 性能测试 ===");
            System.out.println("总耗时：" + totalTime + "ms");
            System.out.println("完成的调用数量：" + completedCount.get());
            System.out.println("平均每次调用耗时：" + (totalTime * 1.0 / callCount) + "ms");

            assertEquals(callCount, completedCount.get(), "所有调用都应完成");
        });
    }

    @Test
    void savePerformance() {
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            var baseDir = "src/test/resources/agent";
            var persistence = new NfsPersistence(baseDir);
            var orgId = "test-org-" + UUID.randomUUID();
            var userId = "test-user-" + UUID.randomUUID();

            var callCount = 1000;
            var completedCount = new AtomicInteger(0);
            var latch = new CountDownLatch(callCount);

            System.out.println("开始执行 " + callCount + " 次 save 调用...");
            var startTime = System.currentTimeMillis();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var i = 0; i < callCount; i++) {
                    var agentId = "test-agent-" + i;
                    var finalI = i;
                    executor.submit(() -> {
                        try {
                            var memories = List.of(
                                    Message.builder()
                                            .id(UUID.randomUUID().toString())
                                            .role(Message.ROLE.USER)
                                            .type(Message.TYPE.TEXT)
                                            .text("测试消息 " + finalI)
                                            .build()
                            );
                            persistence.save(orgId, userId, agentId, List.of(), memories);
                        } finally {
                            completedCount.incrementAndGet();
                            latch.countDown();
                        }
                    });
                }

                latch.await();
            }

            Thread.sleep(500);
            var totalTime = System.currentTimeMillis() - startTime;

            System.out.println("=== NfsPersistence Save 性能测试 ===");
            System.out.println("总耗时：" + totalTime + "ms");
            System.out.println("完成的调用数量：" + completedCount.get());
            System.out.println("平均每次调用耗时：" + (totalTime * 1.0 / callCount) + "ms");

            assertEquals(callCount, completedCount.get(), "所有调用都应完成");
        });
    }

    /**
     * 生成模拟对话文本
     */
    private String generateSimulatedText(String[] templates, int round, int charCount) {
        var builder = new StringBuilder();
        var counter = 0;
        while (builder.length() < charCount) {
            var template = templates[counter % templates.length];
            var topic = "话题" + (counter + round * 10);
            builder.append(template.formatted(topic));
            counter++;
        }

        return builder.substring(0, Math.min(builder.length(), charCount));
    }

    /**
     * 生成具有 2 个 event 和 200 轮对话（约 200kb）的 CBOR 文件
     */
    @Test
    void generateSimulatedCborFile() {
        System.out.println(" generateSimulatedCborFile 开始生成模拟对话 CBOR 文件");

        var baseDir = "src/test/resources/agent";
        var persistence = new NfsPersistence(baseDir);
        var orgId = "test-org-simulated";
        var userId = "test-user-simulated";
        var agentId = "test-agent-simulated";

        var templates = new String[]{
                "你好，我想了解一下关于%s的话题。",
                "请问你能帮我解释一下%s吗？",
                "我对%s很感兴趣，能详细介绍一下吗？",
                "关于%s，我有一些问题想问。",
                "我觉得%s很有意思，你觉得呢？",
                "我在学习%s，遇到了一些困难。",
                "听说%s很重要，是真的吗？",
                "我想深入了解%s的相关知识。",
                "对于%s，你有什么建议吗？",
                "能给我讲讲%s的历史吗？"
        };

        var memories = new ArrayList<Message>();
        var events = new ArrayList<Event>();

        // 生成 200 轮对话，每轮约 500 字符（user + assistant 各 250），总计约 200kb
        var charsPerRound = 400;
        var roundCount = 200;

        for (var round = 0; round < roundCount; round++) {
            var userText = generateSimulatedText(templates, round, charsPerRound / 2);
            var assistantText = generateSimulatedText(templates, round + 1000, charsPerRound / 2);

            var userItem = Message.builder()
                    .id(DataUtil.getFlakeId())
                    .role(Message.ROLE.USER)
                    .type(Message.TYPE.TEXT)
                    .text(userText)
                    .build();
            memories.add(userItem);

            var assistantItem = Message.builder()
                    .id(DataUtil.getFlakeId())
                    .role(Message.ROLE.ASSISTANT)
                    .type(Message.TYPE.TEXT)
                    .text(assistantText)
                    .build();
            memories.add(assistantItem);
        }

        // 添加 2 个 event
        var event1 = Event.builder()
                .priority(1)
                .type(Event.Type.ORGANIZE_MEMORY)
                .message(memories.get(0))
                .build();
        events.add(event1);

        var event2 = Event.builder()
                .priority(2)
                .type(Event.Type.MESSAGE)
                .message(memories.get(1))
                .build();
        events.add(event2);

        // 使用 save 方法生成 CBOR 文件
        persistence.save(orgId, userId, agentId, events, memories);

        var outputFile = "src/test/resources/agent/%s/%s/%s.cbor".formatted(orgId, userId, agentId);
        var fileSize = new File(outputFile).length();

        System.out.println(" generateSimulatedCborFile 文件已生成：" + outputFile);
        System.out.println(" generateSimulatedCborFile 文件大小：" + fileSize + " bytes");
        System.out.println(" generateSimulatedCborFile 对话轮数：" + roundCount);
        System.out.println(" generateSimulatedCborFile Communication 数量：" + memories.size());
        System.out.println(" generateSimulatedCborFile Event 数量：" + events.size());
    }
}
